package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gui.MaterialBreakdownMenu;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /refinery — break higher-tier materials into lower tiers, split out of the armory hub. */
public final class RefineryMenuCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingConfigService craftingConfig;
    private final MaterialBreakdownConfigService breakdownConfig;

    public RefineryMenuCommand(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingConfigService craftingConfig,
            MaterialBreakdownConfigService breakdownConfig
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.breakdownConfig = Objects.requireNonNull(breakdownConfig, "breakdownConfig");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the refinery.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("The refinery is only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            player.sendMessage(Component.text("The menu system is unavailable right now.", NamedTextColor.RED));
            return true;
        }
        guiManager.open(player, new MaterialBreakdownMenu(core, craftingConfig, breakdownConfig));
        return true;
    }
}
