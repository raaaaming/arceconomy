package kr.acda.economy.api

import java.util.UUID

/**
 * 서버 화폐(경제)의 **단일 진실 원천**을 제공하는 서비스.
 *
 * Vault 의 `Economy` 브릿지와 같은 역할이다. 잔액은 [java.util.UUID] 기준으로 저장되며
 * arc-database(SQLite/Exposed)에 영속화된다. 상점·경매·직업·현상금 등 화폐를 다루는
 * 모든 모듈은 자기 DB를 만들지 말고 **이 서비스 하나만** 의존해서 사용한다.
 *
 * ### 소비 예시
 * ```kotlin
 * @ModuleSpec(id = "shop", dependencies = ["arceconomy"])
 * class ShopModule : BaseRuntimeModule() {
 *     override fun onEnable() {
 *         val eco = services.require(EconomyService::class)
 *         if (eco.withdraw(buyer.uniqueId, price).success) giveItem(buyer, item)
 *     }
 * }
 * ```
 *
 * 음수 금액은 허용되지 않으며 [EconomyResult.error] 로 거절된다. 모든 메서드는
 * 스레드 안전하다 — 내부적으로 계정 단위 락으로 잔액 갱신을 직렬화한다.
 */
interface EconomyService {

    /** 화폐 단위 이름 (예: "코인"). */
    val currencyName: String

    /** 화폐 기호 (예: "$"). */
    val currencySymbol: String

    /** 금액을 서버 표기 규칙(기호 + 천단위 구분)에 맞게 문자열로 포맷한다. */
    fun format(amount: Double): String

    /** 계정이 존재하지 않으면 시작 잔액으로 생성하고 현재 잔액을 돌려준다. */
    fun ensureAccount(playerId: UUID, name: String): Double

    /** 현재 잔액. 계정이 없으면 0.0. */
    fun balanceOf(playerId: UUID): Double

    /** [amount] 이상 보유 중인지 여부. */
    fun has(playerId: UUID, amount: Double): Boolean

    /** 입금. */
    fun deposit(playerId: UUID, amount: Double): EconomyResult

    /** 출금. 잔액 부족 시 실패. */
    fun withdraw(playerId: UUID, amount: Double): EconomyResult

    /** 잔액을 특정 값으로 설정(관리자용). */
    fun set(playerId: UUID, amount: Double): EconomyResult

    /** [from] → [to] 송금. 원자적으로 처리되며 실패 시 어느 쪽도 변하지 않는다. */
    fun transfer(from: UUID, to: UUID, amount: Double): EconomyResult

    /** 잔액 상위 [limit] 명(내림차순). */
    fun top(limit: Int): List<BalanceEntry>
}

/**
 * 경제 연산 결과.
 *
 * @property success 성공 여부.
 * @property balance 연산 후(또는 실패 시 현재) 잔액.
 * @property error   실패 사유. 성공이면 null.
 */
data class EconomyResult(
    val success: Boolean,
    val balance: Double,
    val error: String? = null,
) {
    companion object {
        fun ok(balance: Double) = EconomyResult(true, balance, null)
        fun fail(balance: Double, error: String) = EconomyResult(false, balance, error)
    }
}

/** 잔액 순위 한 줄. */
data class BalanceEntry(
    val playerId: UUID,
    val name: String,
    val balance: Double,
)
