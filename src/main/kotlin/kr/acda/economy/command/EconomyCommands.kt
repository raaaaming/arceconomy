package kr.acda.economy.command

import kr.acda.arccore.api.command.ARCCommand
import kr.acda.arccore.api.command.CommandContext
import kr.acda.arccore.api.command.CommandMetadata
import kr.acda.arccore.api.command.CommandResult
import kr.acda.economy.api.EconomyService
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** `/balance [player]` — 자기 또는 대상의 잔액을 조회한다. */
internal class BalanceCommand(private val eco: EconomyService) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "balance",
        aliases = listOf("bal", "money"),
        description = "잔액을 조회합니다.",
        usage = "/balance [플레이어]",
    )

    override fun execute(context: CommandContext): CommandResult {
        val target = context.firstArg
        if (target == null) {
            val player = Bukkit.getPlayerExact(context.sender.name)
                ?: return CommandResult.Failure("§c콘솔은 대상을 지정해야 합니다.")
            context.sender.sendMessage("§e잔액: §a${eco.format(eco.balanceOf(player.uniqueId))}")
            return CommandResult.Success
        }
        val offline = Bukkit.getOfflinePlayerIfCached(target)
            ?: return CommandResult.Failure("§c'$target' 플레이어를 찾을 수 없습니다.")
        context.sender.sendMessage("§e$target 님의 잔액: §a${eco.format(eco.balanceOf(offline.uniqueId))}")
        return CommandResult.Success
    }

    override fun onTabComplete(context: CommandContext): List<String> =
        Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(context.firstArg ?: "", true) }
}

/** `/pay <player> <amount>` — 온라인 플레이어에게 송금한다. */
internal class PayCommand(private val eco: EconomyService) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "pay",
        description = "다른 플레이어에게 돈을 보냅니다.",
        usage = "/pay <플레이어> <금액>",
    )

    override fun execute(context: CommandContext): CommandResult {
        val player = Bukkit.getPlayerExact(context.sender.name)
            ?: return CommandResult.Failure("§c플레이어만 사용할 수 있습니다.")
        if (context.args.size < 2) return CommandResult.InvalidUsage("§7사용법: ${metadata.usage}")

        val target: Player = Bukkit.getPlayerExact(context.args[0])
            ?: return CommandResult.Failure("§c'${context.args[0]}' 님은 접속 중이 아닙니다.")
        val amount = context.args[1].toDoubleOrNull()?.takeIf { it > 0 }
            ?: return CommandResult.Failure("§c올바른 금액을 입력하세요.")

        val result = eco.transfer(player.uniqueId, target.uniqueId, amount)
        if (!result.success) return CommandResult.Failure("§c${result.error}")

        player.sendMessage("§a${target.name} 님에게 ${eco.format(amount)} 를 보냈습니다. (잔액 ${eco.format(result.balance)})")
        target.sendMessage("§a${player.name} 님에게서 ${eco.format(amount)} 를 받았습니다.")
        return CommandResult.Success
    }

    override fun onTabComplete(context: CommandContext): List<String> =
        if (context.args.size <= 1)
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(context.firstArg ?: "", true) }
        else emptyList()
}

/** `/baltop` — 잔액 상위 10명을 보여준다. */
internal class BalTopCommand(private val eco: EconomyService) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "baltop",
        aliases = listOf("moneytop"),
        description = "잔액 순위를 보여줍니다.",
        usage = "/baltop",
    )

    override fun execute(context: CommandContext): CommandResult {
        context.sender.sendMessage("§6§l== 부자 순위 ==")
        eco.top(10).forEachIndexed { i, e ->
            context.sender.sendMessage("§e${i + 1}. §f${e.name} §7- §a${eco.format(e.balance)}")
        }
        return CommandResult.Success
    }
}

/** `/eco <give|take|set> <player> <amount>` — 관리자 잔액 조작. */
internal class EcoAdminCommand(private val eco: EconomyService) : ARCCommand {
    override val metadata = CommandMetadata(
        name = "eco",
        aliases = listOf("economy"),
        permission = "arceconomy.admin",
        description = "관리자 경제 명령.",
        usage = "/eco <give|take|set> <플레이어> <금액>",
    )

    @Suppress("DEPRECATION")
    override fun execute(context: CommandContext): CommandResult {
        if (context.args.size < 3) return CommandResult.InvalidUsage("§7사용법: ${metadata.usage}")
        val op = context.args[0].lowercase()
        val offline = Bukkit.getOfflinePlayer(context.args[1])
        val amount = context.args[2].toDoubleOrNull()?.takeIf { it >= 0 }
            ?: return CommandResult.Failure("§c올바른 금액을 입력하세요.")

        val result = when (op) {
            "give", "add" -> eco.deposit(offline.uniqueId, amount)
            "take", "remove" -> eco.withdraw(offline.uniqueId, amount)
            "set" -> eco.set(offline.uniqueId, amount)
            else -> return CommandResult.InvalidUsage("§7사용법: ${metadata.usage}")
        }
        return if (result.success) {
            context.sender.sendMessage("§a완료. ${context.args[1]} 님의 잔액: ${eco.format(result.balance)}")
            CommandResult.Success
        } else {
            CommandResult.Failure("§c${result.error}")
        }
    }

    override fun onTabComplete(context: CommandContext): List<String> = when (context.args.size) {
        1 -> listOf("give", "take", "set").filter { it.startsWith(context.args[0], true) }
        2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(context.args[1], true) }
        else -> emptyList()
    }
}
