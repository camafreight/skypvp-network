package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.crafting.MedicShopConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gui.MedicMenu;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class MedicMenuCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingMaterialService materials;
    private final MedicShopConfigService medicShop;

    public MedicMenuCommand(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingMaterialService materials,
            MedicShopConfigService medicShop
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.medicShop = Objects.requireNonNull(medicShop, "medicShop");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the medic bay.", NamedTextColor.RED));
            return true;
        }
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text("The medic bay is only available in the extraction hub.", NamedTextColor.RED));
            return true;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            player.sendMessage(Component.text("The menu system is unavailable right now.", NamedTextColor.RED));
            return true;
        }
        guiManager.open(player, new MedicMenu(core, materials, medicShop));
        return true;
    }
}
