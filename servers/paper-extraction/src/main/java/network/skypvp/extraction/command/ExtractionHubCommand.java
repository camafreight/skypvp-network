package network.skypvp.extraction.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.hub.ExtractionHubStationCatalog;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Staff helper for extraction hub NPC wiring (lists preset station actions). */
public final class ExtractionHubCommand implements CommandExecutor, TabCompleter {

    private final ExtractionHubStationCatalog catalog;

    public ExtractionHubCommand(ExtractionHubStationCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("skypvp.breach.hub.admin")) {
            sender.sendMessage(Component.text("You do not have permission to manage hub stations.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || "stations".equalsIgnoreCase(args[0])) {
            sender.sendMessage(Component.text("Extraction hub NPC presets:", NamedTextColor.GOLD));
            for (ExtractionHubStationCatalog.Station station : catalog.stations()) {
                sender.sendMessage(Component.text(
                        " - " + station.id() + ": /npc action " + station.id() + " " + station.actionType() + " " + station.actionData(),
                        NamedTextColor.GRAY
                ));
            }
            sender.sendMessage(Component.text("Place NPCs with /npc create <id>, then set action + hologram from presets above.", NamedTextColor.DARK_GRAY));
            return true;
        }
        if ("placehint".equalsIgnoreCase(args[0]) && sender instanceof Player player) {
            sender.sendMessage(Component.text("Stand at the desired hub station, then:", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/npc create hub_armory", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/npc action hub_armory PLAYER_COMMAND armory", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("Your position: "
                    + player.getWorld().getName() + " "
                    + String.format(Locale.US, "%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ()),
                    NamedTextColor.DARK_GRAY));
            return true;
        }
        sender.sendMessage(Component.text("Usage: /extractionhub [stations|placehint]", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("skypvp.breach.hub.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("stations", "placehint");
        }
        return List.of();
    }
}
