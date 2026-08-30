package kr.acda.economy.internal

import kr.acda.arccore.api.module.ModuleLogger
import kr.acda.economy.api.EconomyService
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * ARCeconomy 를 **Vault 의 `Economy` provider** 로 등록하는 브리지.
 *
 * ARCCore 는 `load: STARTUP` 이라 Vault(POSTWORLD)보다 먼저 로드되므로, 컴파일 시점에 Vault 클래스를
 * 참조하면 런타임에 보이지 않는다. 그래서 **순수 리플렉션 + 동적 프록시**로 처리한다 — Vault 가 켜진 뒤
 * (ServerLoadEvent) Vault 자신의 클래스로더로 `Economy` 인터페이스 프록시를 만들어 등록한다.
 * 이렇게 하면 ARCCore/빌드 설정을 전혀 건드리지 않고 로드 순서와 무관하게 동작한다.
 *
 * `Economy` 는 **인터페이스**라 [Proxy] 로 구현 가능하다(반면 Vault `Permission`/`Chat` 는 추상 클래스라 불가).
 */
internal class VaultEconomyBridge(
    private val plugin: Plugin,
    private val eco: EconomyService,
    private val log: ModuleLogger,
) {
    @Volatile private var registeredProxy: Any? = null

    fun isVaultPresent(): Boolean = Bukkit.getPluginManager().getPlugin("Vault")?.isEnabled == true

    /** Vault 가 존재하면 Economy provider 로 등록한다. 이미 등록됐거나 Vault 가 없으면 무해하게 종료. */
    @Synchronized
    fun register(): Boolean {
        if (registeredProxy != null) return true
        val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return false
        val cl = vault.javaClass.classLoader
        return try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, cl)
            val responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, cl)
            val responseTypeClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse\$ResponseType", true, cl)

            val proxy = Proxy.newProxyInstance(cl, arrayOf(economyClass), EconomyHandler(eco, responseClass, responseTypeClass))

            // ServicesManager.register(Class, Object, Plugin, ServicePriority) 를 리플렉션으로 호출
            // (제네릭 시그니처라 Kotlin 에서 직접 호출이 어렵다).
            val sm = Bukkit.getServicesManager()
            sm.javaClass
                .getMethod("register", Class::class.java, Any::class.java, Plugin::class.java, ServicePriority::class.java)
                .invoke(sm, economyClass, proxy, plugin, ServicePriority.Highest)

            registeredProxy = proxy
            log.info("Vault 연동 활성화 — ARCeconomy 가 Vault Economy provider 로 등록되었습니다.")
            true
        } catch (t: Throwable) {
            log.warn("Vault 연동 실패(무시하고 계속): ${t.message}")
            false
        }
    }

    fun unregister() {
        val proxy = registeredProxy ?: return
        try {
            Bukkit.getServicesManager().unregister(proxy)
        } catch (_: Throwable) {
            // 서버 종료 중이면 무시
        }
        registeredProxy = null
    }

    /**
     * Vault `Economy` 인터페이스 메서드를 [EconomyService] 로 위임하는 InvocationHandler.
     * 메서드 이름으로 디스패치하며, 플레이어 식별자는 [OfflinePlayer] 또는 이름(String) 둘 다 받는다.
     */
    private class EconomyHandler(
        private val eco: EconomyService,
        responseClass: Class<*>,
        responseTypeClass: Class<*>,
    ) : InvocationHandler {

        private val responseCtor = responseClass.getConstructor(
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, responseTypeClass, String::class.java,
        )
        private val constants = responseTypeClass.enumConstants
        private val success = constants.first { it.toString() == "SUCCESS" }
        private val failure = constants.first { it.toString() == "FAILURE" }
        private val notImplemented = constants.first { it.toString() == "NOT_IMPLEMENTED" }

        private fun response(amount: Double, balance: Double, type: Any, error: String?): Any =
            responseCtor.newInstance(amount, balance, type, error)

        @Suppress("DEPRECATION")
        private fun uuidOf(arg: Any?): UUID = when (arg) {
            is OfflinePlayer -> arg.uniqueId
            is String -> Bukkit.getOfflinePlayer(arg).uniqueId
            else -> throw IllegalArgumentException("unsupported account identifier: $arg")
        }

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val a = args ?: emptyArray()
            return when (method.name) {
                "isEnabled" -> true
                "getName" -> "ARCeconomy"
                "hasBankSupport" -> false
                "fractionalDigits" -> 2
                "format" -> eco.format(a[0] as Double)
                "currencyNamePlural", "currencyNameSingular" -> eco.currencyName
                "hasAccount" -> true
                "createPlayerAccount" -> true
                "getBalance" -> eco.balanceOf(uuidOf(a[0]))
                "has" -> eco.has(uuidOf(a[0]), a.last() as Double)
                "withdrawPlayer" -> {
                    val amount = a.last() as Double
                    val r = eco.withdraw(uuidOf(a[0]), amount)
                    response(amount, r.balance, if (r.success) success else failure, r.error)
                }
                "depositPlayer" -> {
                    val amount = a.last() as Double
                    val r = eco.deposit(uuidOf(a[0]), amount)
                    response(amount, r.balance, if (r.success) success else failure, r.error)
                }
                "getBanks" -> emptyList<String>()
                "createBank", "deleteBank", "bankBalance", "bankHas", "bankWithdraw",
                "bankDeposit", "isBankOwner", "isBankMember",
                -> response(0.0, 0.0, notImplemented, "ARCeconomy 는 은행 계좌를 지원하지 않습니다.")
                // Object 기본 메서드
                "equals" -> proxy === a.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ARCeconomyVaultBridge"
                else -> defaultFor(method.returnType)
            }
        }

        private fun defaultFor(returnType: Class<*>): Any? = when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Double.TYPE -> 0.0
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    }
}
