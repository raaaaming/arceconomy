package kr.acda.economy.internal

import kr.acda.arccore.database.service.SqliteDatabase
import kr.acda.economy.api.BalanceEntry
import kr.acda.economy.api.EconomyResult
import kr.acda.economy.api.EconomyService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [EconomyService] 기본 구현. arc-database 의 [SqliteDatabase] 위에서 동작한다.
 *
 * 잔액 갱신은 계정 UUID 단위 락으로 직렬화해 두 스레드가 같은 계정을 동시에 갱신하는
 * lost-update 를 막는다. 송금은 두 계정 락을 **UUID 정렬 순서**로 획득해 데드락을 피한다.
 */
internal class EconomyServiceImpl(
    private val db: SqliteDatabase,
    override val currencyName: String,
    override val currencySymbol: String,
    private val startingBalance: Double,
) : EconomyService {

    private val locks = ConcurrentHashMap<UUID, ReentrantLock>()
    private val moneyFormat = DecimalFormat("#,##0.00")

    private fun lockFor(id: UUID) = locks.computeIfAbsent(id) { ReentrantLock() }

    override fun format(amount: Double): String = "$currencySymbol${moneyFormat.format(amount)}"

    override fun ensureAccount(playerId: UUID, name: String): Double = lockFor(playerId).withLock {
        db.transaction {
            val row = Balances.selectAll().where { Balances.playerId eq playerId.toString() }.firstOrNull()
            if (row == null) {
                Balances.insert {
                    it[Balances.playerId] = playerId.toString()
                    it[Balances.name] = name.take(16)
                    it[balance] = startingBalance
                }
                startingBalance
            } else {
                // 표시 이름 최신화
                if (row[Balances.name] != name.take(16)) {
                    Balances.update({ Balances.playerId eq playerId.toString() }) {
                        it[Balances.name] = name.take(16)
                    }
                }
                row[Balances.balance]
            }
        }
    }

    override fun balanceOf(playerId: UUID): Double = db.transaction {
        Balances.selectAll().where { Balances.playerId eq playerId.toString() }
            .firstOrNull()?.get(Balances.balance) ?: 0.0
    }

    override fun has(playerId: UUID, amount: Double): Boolean = balanceOf(playerId) >= amount

    override fun deposit(playerId: UUID, amount: Double): EconomyResult {
        if (amount < 0) return EconomyResult.fail(balanceOf(playerId), "금액은 0 이상이어야 합니다.")
        return lockFor(playerId).withLock {
            val new = readOrCreate(playerId) + amount
            writeBalance(playerId, new)
            EconomyResult.ok(new)
        }
    }

    override fun withdraw(playerId: UUID, amount: Double): EconomyResult {
        if (amount < 0) return EconomyResult.fail(balanceOf(playerId), "금액은 0 이상이어야 합니다.")
        return lockFor(playerId).withLock {
            val current = readOrCreate(playerId)
            if (current < amount) return@withLock EconomyResult.fail(current, "잔액이 부족합니다.")
            val new = current - amount
            writeBalance(playerId, new)
            EconomyResult.ok(new)
        }
    }

    override fun set(playerId: UUID, amount: Double): EconomyResult {
        if (amount < 0) return EconomyResult.fail(balanceOf(playerId), "금액은 0 이상이어야 합니다.")
        return lockFor(playerId).withLock {
            readOrCreate(playerId)
            writeBalance(playerId, amount)
            EconomyResult.ok(amount)
        }
    }

    override fun transfer(from: UUID, to: UUID, amount: Double): EconomyResult {
        if (from == to) return EconomyResult.fail(balanceOf(from), "자기 자신에게는 보낼 수 없습니다.")
        if (amount <= 0) return EconomyResult.fail(balanceOf(from), "0보다 큰 금액만 보낼 수 있습니다.")

        // 데드락 방지: 항상 작은 UUID 락을 먼저 획득
        val (first, second) = if (from < to) from to to else to to from
        return lockFor(first).withLock {
            lockFor(second).withLock {
                val fromBal = readOrCreate(from)
                if (fromBal < amount) return@withLock EconomyResult.fail(fromBal, "잔액이 부족합니다.")
                readOrCreate(to)
                writeBalance(from, fromBal - amount)
                writeBalance(to, balanceOf(to) + amount)
                EconomyResult.ok(fromBal - amount)
            }
        }
    }

    override fun top(limit: Int): List<BalanceEntry> = db.transaction {
        Balances.selectAll()
            .orderBy(Balances.balance, SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .map { BalanceEntry(UUID.fromString(it[Balances.playerId]), it[Balances.name], it[Balances.balance]) }
    }

    /** 락 보유 상태에서 호출. 계정이 없으면 시작 잔액으로 만들고 값을 돌려준다. */
    private fun readOrCreate(playerId: UUID): Double = db.transaction {
        val row = Balances.selectAll().where { Balances.playerId eq playerId.toString() }.firstOrNull()
        if (row != null) return@transaction row[Balances.balance]
        Balances.insert {
            it[Balances.playerId] = playerId.toString()
            it[name] = "?"
            it[balance] = startingBalance
        }
        startingBalance
    }

    private fun writeBalance(playerId: UUID, value: Double) = db.transaction {
        Balances.update({ Balances.playerId eq playerId.toString() }) { it[balance] = value }
    }
}
