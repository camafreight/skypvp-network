package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gameplay.scrapper.ScrapperMenu;
import network.skypvp.extraction.gameplay.scrapper.ScrapperService;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ScrapperCommand implements CommandExecutor {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final ScrapperService scrapperService;
    private final CraftingConfigService craftingConfig;

    public ScrapperCommand(
            PaperCorePlugin core,
            BreachEngine engine,
            ScrapperService scrapperService,
            CraftingConfigService craftingConfig
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        scrapperService.warmPlayer(player);
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text(
                    "Visit the Scrap Technician in the extraction hub to collect salvage.",
                    NamedTextColor.RED
            ));
            return true;
        }
        core.guiManager().open(player, new ScrapperMenu(core, scrapperService, craftingConfig));
        return true;
    }
}
