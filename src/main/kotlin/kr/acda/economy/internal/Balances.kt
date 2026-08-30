package kr.acda.economy.internal

import org.jetbrains.exposed.v1.core.Table

/**
 * 잔액 테이블. 계정당 한 행. [name] 은 baltop 표시용으로 접속 시 갱신된다.
 */
internal object Balances : Table("balances") {
    val playerId = varchar("player_id", 36)
    val name = varchar("name", 16)
    val balance = double("balance")
    override val primaryKey = PrimaryKey(playerId)
}
