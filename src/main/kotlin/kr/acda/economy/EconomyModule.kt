package kr.acda.economy

import kr.acda.arccore.api.module.ModuleSpec
import kr.acda.arccore.core.ArcCorePlugin
import kr.acda.arccore.database.service.SqliteStorageService
import kr.acda.arccore.runtime.context.BaseRuntimeModule
import kr.acda.economy.api.EconomyService
import kr.acda.economy.command.BalTopCommand
import kr.acda.economy.command.BalanceCommand
import kr.acda.economy.command.EcoAdminCommand
import kr.acda.economy.command.PayCommand
import kr.acda.economy.internal.Balances
import kr.acda.economy.internal.EconomyServiceImpl
import kr.acda.economy.internal.VaultEconomyBridge
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.ServerLoadEvent

/**
 * ARCeconomy — 서버 화폐의 단일 진실 원천.
 *
 * arc-database 위에 잔액을 저장하고 [EconomyService] 를 exports 해, 상점/경매/직업/현상금 등
 * 화폐를 다루는 모든 모듈이 자기 DB 없이 이 서비스 하나만 의존하도록 한다.
 */
@ModuleSpec(
    id = "arceconomy",
    name = "ARCeconomy",
    version = "1.0.0",
    description = "Central Vault-style economy service backed by arc-database",
    authors = ["raaaaming"],
    dependencies = ["arc-database", "arcplaceholder?"],
    exports = ["kr.acda.economy.api"],
)
class EconomyModule : BaseRuntimeModule() {

    private var service: EconomyServiceImpl? = null
    private var papi: kr.acda.placeholder.api.PlaceholderService? = null
    private var vaultBridge: VaultEconomyBridge? = null

    override fun onEnable() {
        super.onEnable()

        val storage = services.require(SqliteStorageService::class)
        val db = storage.open("economy", Balances)

        val svc = EconomyServiceImpl(
            db = db,
            currencyName = "코인",
            currencySymbol = "$",
            startingBalance = 100.0,
        )
        service = svc
        services.register(EconomyService::class, svc)

        commands.register(BalanceCommand(svc))
        commands.register(PayCommand(svc))
        commands.register(BalTopCommand(svc))
        commands.register(EcoAdminCommand(svc))

        listeners.register(JoinListener(svc), ArcCorePlugin.instance)

        registerPlaceholders(svc)
        setupVaultBridge(svc)

        logger.info("ARCeconomy enabled — EconomyService registered (currency=${svc.currencyName})")
    }

    /**
     * Vault Economy provider 로 등록한다. ARCCore 가 STARTUP 로드라 이 시점엔 Vault(POSTWORLD)가 아직
     * 없으므로, 즉시 시도(플러그인 reload 대비) + [ServerLoadEvent] 로 Vault enable 후 재시도한다.
     */
    private fun setupVaultBridge(eco: EconomyService) {
        val bridge = VaultEconomyBridge(ArcCorePlugin.instance, eco, logger)
        vaultBridge = bridge
        if (!bridge.register()) {
            listeners.register(object : Listener {
                @EventHandler
                fun onServerLoad(event: ServerLoadEvent) {
                    bridge.register()
                }
            }, ArcCorePlugin.instance)
        }
    }

    /** arcplaceholder 가 있으면 `%eco_balance%`, `%eco_raw%`, `%eco_top_N%` 를 등록한다. */
    private fun registerPlaceholders(eco: EconomyService) {
        val svc = services.get(kr.acda.placeholder.api.PlaceholderService::class) ?: return
        papi = svc
        svc.register("eco", kr.acda.placeholder.api.PlaceholderProvider { player, key ->
            when {
                key == "balance" -> player?.let { eco.format(eco.balanceOf(it.uniqueId)) }
                key == "raw" -> player?.let { eco.balanceOf(it.uniqueId).toString() }
                key == "currency" -> eco.currencyName
                key.startsWith("top_") -> {
                    val rank = key.removePrefix("top_").toIntOrNull() ?: return@PlaceholderProvider null
                    eco.top(rank).getOrNull(rank - 1)?.let { "${it.name}: ${eco.format(it.balance)}" } ?: "-"
                }
                else -> null
            }
        })
        logger.info("ARCeconomy: placeholder 연동 활성화 (%eco_balance% 등)")
    }

    override fun onDisable() {
        vaultBridge?.unregister()
        vaultBridge = null
        papi?.runCatching { unregister("eco") }
        papi = null
        service?.let { services.unregister(EconomyService::class) }
        service = null
        logger.info("ARCeconomy disabled")
        super.onDisable()
    }

    /** 접속 시 계정 생성 + 표시 이름 최신화. */
    private class JoinListener(private val eco: EconomyServiceImpl) : Listener {
        @EventHandler
        fun onJoin(event: PlayerJoinEvent) {
            eco.ensureAccount(event.player.uniqueId, event.player.name)
        }
    }
}
