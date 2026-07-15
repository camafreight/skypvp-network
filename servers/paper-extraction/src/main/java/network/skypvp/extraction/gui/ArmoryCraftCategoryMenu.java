package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import network.skypvp.extraction.item.MedicConsumableType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.PaginatedGuiMenu;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.shared.currency.CurrencyFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Paginated recipe list for one craft workbench category. */
public final class ArmoryCraftCategoryMenu implements GuiMenu {

    private final PaperCorePlugin core;
    private final HubEconomyService economy;
    private final ArmoryCraftCatalog.Category category;
    private boolean sortPriceAscending = true;
    private final PaginatedGuiMenu<ArmoryCraftCatalog.Recipe> delegate;

    public ArmoryCraftCategoryMenu(PaperCorePlugin core, HubEconomyService economy, ArmoryCraftCatalog.Category category) {
        this.core = Objects.requireNonNull(core, "core");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.category = Objects.requireNonNull(category, "category");
        this.delegate = PaginatedGuiMenu.<ArmoryCraftCatalog.Recipe>create(
                        Component.text(category.title(), NamedTextColor.GOLD), ExtractionGuiLayout.SIZE)
                .pageSlots(ExtractionGuiLayout.PAGE_SLOTS)
                .entries(ignored -> recipesFor())
                .renderItem(this::renderRecipe)
                .onItemClick(this::craft)
                .button(ExtractionGuiLayout.CLOSE_SLOT, viewer -> GuiButtonLibrary.close("Close crafting"), GuiClickContext::close)
                .button(ExtractionGuiLayout.BACK_SLOT, viewer -> GuiButtonLibrary.back("Return to categories"), ExtractionGuiLayout::backOrClose)
                .button(ExtractionGuiLayout.HEADER_SLOT, viewer -> GuiButtonLibrary.menuHeader(
                        "<gold>" + category.title(),
                        lore -> categoryHeaderLore().forEach(lore::plain)
                ), ctx -> {
                })
                .button(ExtractionGuiLayout.SORT_SLOT, viewer -> sortButton(), this::toggleSort)
                .button(ExtractionGuiLayout.WALLET_SLOT, viewer -> GuiItems.named(Material.GOLD_INGOT, "<gold>Coins", List.of(
                        "<gray>Balance: <gold>" + CurrencyFormat.formatCoins(economy.cachedCoins(viewer.getUniqueId())) + " coins"
                )), ctx -> {
                })
                .previousButton(ExtractionGuiLayout.PREVIOUS_PAGE_SLOT, GuiButtonLibrary::previousPage)
                .nextButton(ExtractionGuiLayout.NEXT_PAGE_SLOT, GuiButtonLibrary::nextPage)
                .build();
    }

    @Override
    public Component title() {
        return delegate.title();
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public void render(Player viewer, org.bukkit.inventory.Inventory inventory) {
        delegate.render(viewer, inventory);
    }

    @Override
    public void onClick(GuiClickContext context) {
        delegate.onClick(context);
    }

    private List<String> categoryHeaderLore() {
        return switch (category) {
            case ARMOR -> List.of(
                    "<gray>Spend coins to mint Infuse armor.",
                    "<gray>Helmet, chestplate, leggings, and boots."
            );
            case MEDICAL -> List.of(
                    "<gray>Craft healing items and stamina syringes.",
                    "<gray>Stats are shown on each recipe."
            );
            case SHIELD -> List.of(
                    "<gray>Craft shield modules by rarity.",
                    "<gray>Socket them in the Infuse bench."
            );
            case MODULE -> List.of(
                    "<gray>Craft armor modules and overclocks.",
                    "<gray>Socket them in Infuse chestplates."
            );
        };
    }

    private List<ArmoryCraftCatalog.Recipe> recipesFor() {
        List<ArmoryCraftCatalog.Recipe> recipes = new ArrayList<>(ArmoryCraftCatalog.byCategory(category));
        Comparator<ArmoryCraftCatalog.Recipe> comparator = Comparator
                .comparingLong(ArmoryCraftCatalog.Recipe::coinCost)
                .thenComparing(ArmoryCraftCatalog.Recipe::displayName, String.CASE_INSENSITIVE_ORDER);
        if (!sortPriceAscending) {
            comparator = comparator.reversed();
        }
        recipes.sort(comparator);
        return recipes;
    }

    private ItemStack sortButton() {
        String order = sortPriceAscending ? "Low to High" : "High to Low";
        return GuiButtonLibrary.secondaryAction(Material.COMPARATOR, "Sort", lore -> {
            lore.fact("Price", order);
            lore.footer("<gray>", "Click to toggle");
        });
    }

    private void toggleSort(GuiClickContext context) {
        sortPriceAscending = !sortPriceAscending;
        context.reopen(this);
    }

    private ItemStack renderRecipe(Player viewer, ArmoryCraftCatalog.Recipe recipe) {
        List<String> lore = new ArrayList<>();
        if (recipe.category() == ArmoryCraftCatalog.Category.MEDICAL) {
            MedicConsumableType.byRecipeId(recipe.id()).ifPresent(type -> {
                lore.add("<gray>" + type.blurb());
                lore.add("");
                lore.addAll(type.statMiniMessageLines());
                lore.add("");
            });
        }
        lore.add("<gray>Cost: <gold>" + CurrencyFormat.formatCoins(recipe.coinCost()) + " coins");
        lore.add("");
        lore.add("<yellow>Click to craft");
        return GuiItems.named(recipe.icon(), "<white>" + recipe.displayName(), lore);
    }

    private void craft(GuiClickContext context, ArmoryCraftCatalog.Recipe recipe) {
        Player player = context.viewer();
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(player, "<red>Crafting is unavailable right now.");
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Make room in your inventory first.");
            return;
        }
        long cost = recipe.coinCost();
        economy.trySpendCoins(player, cost).thenAcceptAsync(spent -> core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!spent) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Not enough coins. Need <gold>" + CurrencyFormat.formatCoins(cost) + "<red>.");
                ArmoryHubMenu.notifyCoins(player, economy.cachedCoins(player.getUniqueId()));
                return;
            }
            ItemStack crafted = recipe.crafter().apply(service);
            if (crafted == null || crafted.getType().isAir()) {
                economy.refundCoins(player.getUniqueId(), cost);
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Craft failed — coins refunded.");
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(crafted);
            if (!leftovers.isEmpty()) {
                economy.refundCoins(player.getUniqueId(), cost);
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Inventory full — coins refunded.");
                return;
            }
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
            send(player, "<green>Crafted <white>" + recipe.displayName() + "<green>.");
            ArmoryHubMenu.notifyCoins(player, economy.cachedCoins(player.getUniqueId()));
            context.reopen(this);
        }));
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
