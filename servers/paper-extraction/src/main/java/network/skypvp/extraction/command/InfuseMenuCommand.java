package network.skypvp.extraction.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gui.InfuseMenu;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Opens the Infuse &amp; Overclocking menu via the core GUI framework. */
public final class InfuseMenuCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;

    public InfuseMenuCommand(PaperCorePlugin core, BreachEngine engine) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the Infuse menu.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("Infuse stations are only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            player.sendMessage(Component.text("The menu system is unavailable right now.", NamedTextColor.RED));
            return true;
        }
        // Single smart bench: it detects whichever Infuse piece is deposited.
        guiManager.open(player, new InfuseMenu(core, false));
        return true;
    }
}
