package network.skypvp.extraction.command;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.BlackMarketConfigService;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingReloadResult;
import network.skypvp.extraction.crafting.CraftingMaterialLoreContributor;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.crafting.ItemConfigService;
import network.skypvp.extraction.crafting.MedicShopConfigService;
import network.skypvp.extraction.crafting.MaterialBreakdownConfigService;
import network.skypvp.extraction.stash.MaterialStashTierConfigService;
import network.skypvp.extraction.gameplay.ExtractionLootFactory;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/** Reloads crafting JSON configs without restarting the server. */
public final class CraftingReloadCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final BlackMarketConfigService blackMarket;
    private final ItemConfigService itemConfig;
    private final MedicShopConfigService medicShop;
    private final MaterialBreakdownConfigService breakdownConfig;
    private final MaterialStashTierConfigService stashTiers;
    private final BlueprintDiscoveryService discovery;
    private final BreachGameplayCoordinator gameplayCoordinator;

    public CraftingReloadCommand(
            JavaPlugin plugin,
            PaperCorePlugin core,
            CraftingConfigService craftingConfig,
            BlackMarketConfigService blackMarket,
            ItemConfigService itemConfig,
            MedicShopConfigService medicShop,
            MaterialBreakdownConfigService breakdownConfig,
            MaterialStashTierConfigService stashTiers,
            BlueprintDiscoveryService discovery,
            BreachGameplayCoordinator gameplayCoordinator
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.blackMarket = Objects.requireNonNull(blackMarket, "blackMarket");
        this.itemConfig = Objects.requireNonNull(itemConfig, "itemConfig");
        this.medicShop = Objects.requireNonNull(medicShop, "medicShop");
        this.breakdownConfig = Objects.requireNonNull(breakdownConfig, "breakdownConfig");
        this.stashTiers = Objects.requireNonNull(stashTiers, "stashTiers");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.gameplayCoordinator = Objects.requireNonNull(gameplayCoordinator, "gameplayCoordinator");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("skypvp.breach.crafting.reload")) {
            sender.sendMessage(Component.text("You do not have permission to reload crafting configs.", NamedTextColor.RED));
            return true;
        }
        CraftingReloadResult crafting = craftingConfig.reload(plugin.getLogger());
        discovery.refreshCatalog(craftingConfig.blueprints());
        blackMarket.reload(plugin.getLogger());
        itemConfig.reload(plugin.getLogger());
        medicShop.reload(plugin.getLogger());
        breakdownConfig.reload(plugin.getLogger());
        stashTiers.reload(plugin.getLogger());
        CraftingMaterialLoreContributor.bind(craftingConfig);
        ItemConfigOverrides.bind(itemConfig);
        ItemConfigOverrides.bindMedicShop(medicShop);
        gameplayCoordinator.lootService().bindExtractionLoot(new ExtractionLootFactory(core, craftingConfig));
        sender.sendMessage(Component.text(
                "Reloaded crafting: " + crafting.materials() + " materials, " + crafting.blueprints() + " blueprints, "
                        + breakdownConfig.recipes().size() + " breakdown recipes, "
                        + stashTiers.tiers().size() + " stash tiers, "
                        + blackMarket.listings().size() + " black market listings, "
                        + itemConfig.overrides().size() + " item overrides, "
                        + medicShop.listings().size() + " medic shop listings.",
                NamedTextColor.GREEN
        ));
        return true;
    }
}
