package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gui.BlueprintSelectMenu;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /craft — blueprint craft workbench, split out of the armory hub. */
public final class CraftWorkbenchMenuCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingConfigService craftingConfig;
    private final CraftingMaterialService materials;
    private final BlueprintDiscoveryService discovery;
    private final WeaponMechanicsBridge weaponBridge;

    public CraftWorkbenchMenuCommand(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingConfigService craftingConfig,
            CraftingMaterialService materials,
            BlueprintDiscoveryService discovery,
            WeaponMechanicsBridge weaponBridge
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.weaponBridge = Objects.requireNonNull(weaponBridge, "weaponBridge");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the craft workbench.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("The craft workbench is only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            player.sendMessage(Component.text("The menu system is unavailable right now.", NamedTextColor.RED));
            return true;
        }
        guiManager.open(player, new BlueprintSelectMenu(core, craftingConfig, materials, discovery, weaponBridge));
        materials.ensureStarterKit(player.getUniqueId());
        return true;
    }
}
