package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gui.ArmoryHubMenu;
import network.skypvp.extraction.gui.HubEconomyService;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Armory hub: Infuse, Salvage, Mark Upgrade, Shield Repair (+ stash in the header). */
public final class ArmoryMenuCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingMaterialService materials;
    private final HubEconomyService economy;

    public ArmoryMenuCommand(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingMaterialService materials,
            HubEconomyService economy
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the armory.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("The armory is only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            player.sendMessage(Component.text("The menu system is unavailable right now.", NamedTextColor.RED));
            return true;
        }
        guiManager.open(player, new ArmoryHubMenu(core, materials, economy).build().build());
        materials.ensureStarterKit(player.getUniqueId());
        return true;
    }
}
