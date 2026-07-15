package network.skypvp.paper.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import network.skypvp.paper.gui.PlayBlockItems;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PlayBlocksCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<red>Only players can use this command."));
            return true;
        }
        if (!sender.hasPermission("skypvp.staff")) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent("<red>You do not have permission."));
            return true;
        }
        if (args.length == 0 || !"give".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent(
                    "<gray>Usage: /playblocks give <01|02|both>"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent(
                    "<gray>Usage: /playblocks give <01|02|both>"));
            return true;
        }
        String variant = args[1].trim().toLowerCase(Locale.ROOT);
        List<ItemStack> stacks = switch (variant) {
            case "01", "1", "pl" -> List.of(PlayBlockItems.playBlock01());
            case "02", "2", "ay" -> List.of(PlayBlockItems.playBlock02());
            case "both", "all" -> List.of(PlayBlockItems.playBlock01(), PlayBlockItems.playBlock02());
            default -> List.of();
        };
        if (stacks.isEmpty()) {
            sender.sendMessage(ServerTextUtil.miniMessageComponent(
                    "<red>Unknown variant. Use <white>01<red>, <white>02<red>, or <white>both<red>."));
            return true;
        }
        for (ItemStack stack : stacks) {
            give(player, stack);
        }
        sender.sendMessage(ServerTextUtil.miniMessageComponent(
                "<green>Gave play block(s). Face <white>north <green>toward the logo; place as a light block."));
        return true;
    }

    private static void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("skypvp.staff")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("give"), args[0]);
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("01", "02", "both"), args[1]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
