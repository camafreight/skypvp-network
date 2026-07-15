package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.stash.MaterialStashGuiService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Opens the material stash from anywhere in the extraction hub. */
public final class StashCommand implements CommandExecutor {

    private final BreachEngine engine;
    private final MaterialStashGuiService stashGui;

    public StashCommand(BreachEngine engine, MaterialStashGuiService stashGui) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stashGui = Objects.requireNonNull(stashGui, "stashGui");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the material stash.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("The material stash is only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        stashGui.open(player, null);
        return true;
    }
}
