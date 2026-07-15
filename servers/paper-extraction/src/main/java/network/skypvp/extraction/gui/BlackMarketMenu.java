package network.skypvp.extraction.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.BlackMarketConfigService;
import network.skypvp.extraction.item.ArmorSetBonusTexts;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.InfuseArmorPiece;
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

/** Coin and gold shop for pre-built armor set kits. */
public final class BlackMarketMenu implements GuiMenu {

    private enum PaymentMode {
        COINS("Coins"),
        GOLD("Gold");

        private final String label;

        PaymentMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        PaymentMode next() {
            return this == COINS ? GOLD : COINS;
        }
    }

    private final PaperCorePlugin core;
    private final HubEconomyService economy;
    private final BlackMarketConfigService config;
    private PaymentMode paymentMode = PaymentMode.COINS;
    private final PaginatedGuiMenu<BlackMarketConfigService.Listing> delegate;

    public BlackMarketMenu(PaperCorePlugin core, HubEconomyService economy, BlackMarketConfigService config) {
        this.core = Objects.requireNonNull(core, "core");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.config = Objects.requireNonNull(config, "config");
        this.delegate = PaginatedGuiMenu.<BlackMarketConfigService.Listing>create(
                        Component.text("Black Market", NamedTextColor.DARK_PURPLE), ExtractionGuiLayout.SIZE)
                .pageSlots(ExtractionGuiLayout.PAGE_SLOTS)
                .entries(ignored -> config.listings())
                .renderItem(this::renderListing)
                .onItemClick(this::purchase)
                .button(ExtractionGuiLayout.CLOSE_SLOT, viewer -> GuiButtonLibrary.close("Close black market"), GuiClickContext::close)
                .button(ExtractionGuiLayout.BACK_SLOT, viewer -> GuiButtonLibrary.back("Return to the armory"), ExtractionGuiLayout::backOrClose)
                .button(ExtractionGuiLayout.HEADER_SLOT, viewer -> headerButton(viewer), ctx -> {
                })
                .button(ExtractionGuiLayout.SORT_SLOT, viewer -> paymentModeButton(), this::togglePaymentMode)
                .button(ExtractionGuiLayout.WALLET_SLOT, viewer -> walletButton(viewer), ctx -> {
                })
                .previousButton(ExtractionGuiLayout.PREVIOUS_PAGE_SLOT, GuiButtonLibrary::previousPage)
                .nextButton(ExtractionGuiLayout.NEXT_PAGE_SLOT, GuiButtonLibrary::nextPage)
                .liveRefresh(true)
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

    private ItemStack headerButton(Player viewer) {
        return GuiItems.named(Material.EMERALD, "<dark_purple>Black Market", List.of(
                "<gray>Buy complete armor kits with currency.",
                "<gray>Sets are clean — no sockets attached.",
                "",
                "<gray>Payment: <white>" + paymentMode.label(),
                "<gold>Coins: <yellow>" + CurrencyFormat.formatCoins(economy.cachedCoins(viewer.getUniqueId())),
                "<yellow>Gold: <yellow>" + CurrencyFormat.formatGold(economy.cachedGold(viewer.getUniqueId()))
        ));
    }

    private ItemStack walletButton(Player viewer) {
        if (paymentMode == PaymentMode.GOLD) {
            long gold = economy.cachedGold(viewer.getUniqueId());
            return GuiItems.named(Material.GOLD_NUGGET, "<yellow>Gold Wallet", List.of(
                    "<gray>Balance: <yellow>" + CurrencyFormat.formatGold(gold) + " gold",
                    "<dark_gray>Toggle payment mode in slot 5."
            ));
        }
        long coins = economy.cachedCoins(viewer.getUniqueId());
        return GuiItems.named(Material.GOLD_INGOT, "<gold>Coin Wallet", List.of(
                "<gray>Balance: <yellow>" + CurrencyFormat.formatCoins(coins) + " coins",
                "<dark_gray>Toggle payment mode in slot 5."
        ));
    }

    private ItemStack paymentModeButton() {
        return GuiButtonLibrary.secondaryAction(Material.EMERALD, "Payment", lore -> {
            lore.fact("Mode", paymentMode.label());
            lore.footer("<gray>", "Click to toggle coins / gold");
        });
    }

    private void togglePaymentMode(GuiClickContext context) {
        paymentMode = paymentMode.next();
        context.reopen(this);
    }

    private ItemStack renderListing(Player viewer, BlackMarketConfigService.Listing listing) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + listing.armorSet().displayName() + " · " + listing.rarity().displayName());
        if (!listing.description().isBlank()) {
            lore.add("<dark_gray>" + listing.description());
        }
        lore.add("");
        for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
            lore.addAll(ArmorSetBonusTexts.miniMessageLines(listing.armorSet(), piece.setBonusShare()));
            break;
        }
        lore.add("");
        long cost = costFor(listing);
        long owned = balanceFor(viewer, paymentMode);
        if (cost <= 0L) {
            lore.add("<red>Not sold for " + paymentMode.label().toLowerCase());
        } else {
            String currency = paymentMode == PaymentMode.COINS ? "coins" : "gold";
            lore.add("<gold>Cost: <yellow>" + CurrencyFormat.format(cost) + " " + currency);
            lore.add(owned >= cost ? "<green>Click to purchase" : "<red>Insufficient balance <gray>(" + CurrencyFormat.format(owned) + " owned)");
        }
        // Canonical chestplate for the set/rarity so the preview matches the real armor art.
        ItemStack canonical = network.skypvp.extraction.item.ExtractionCustomItemProvider.createInfuseArmor(
                core.customItemService(), InfuseArmorPiece.CHESTPLATE, listing.rarity(), listing.armorSet());
        if (canonical != null) {
            return GuiItems.restyled(canonical, "<light_purple>" + listing.displayName(), lore);
        }
        return GuiItems.named(listing.icon(), "<light_purple>" + listing.displayName(), lore);
    }

    private void purchase(GuiClickContext context, BlackMarketConfigService.Listing listing) {
        Player player = context.viewer();
        CustomItemService service = core.customItemService();
        if (service == null) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Item service unavailable.");
            return;
        }
        long cost = costFor(listing);
        if (cost <= 0L) {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>This kit is not sold for " + paymentMode.label().toLowerCase() + ".");
            return;
        }
        if (paymentMode == PaymentMode.COINS) {
            purchaseWithCoins(context, player, service, listing, cost);
        } else {
            purchaseWithGold(context, player, service, listing, cost);
        }
    }

    private void purchaseWithCoins(
            GuiClickContext context,
            Player player,
            CustomItemService service,
            BlackMarketConfigService.Listing listing,
            long cost
    ) {
        economy.trySpendCoins(player, cost).thenAccept(spent -> core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                if (spent) {
                    economy.refundCoins(player.getUniqueId(), cost);
                }
                return;
            }
            if (!spent) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Not enough coins. Need <yellow>" + CurrencyFormat.formatCoins(cost) + "<red>.");
                context.reopen(this);
                return;
            }
            deliverKit(context, player, service, listing, () -> economy.refundCoins(player.getUniqueId(), cost), cost, "coins");
        }));
    }

    private void purchaseWithGold(
            GuiClickContext context,
            Player player,
            CustomItemService service,
            BlackMarketConfigService.Listing listing,
            long cost
    ) {
        economy.trySpendGold(player, cost).thenAccept(spent -> core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                if (spent) {
                    economy.refundGold(player.getUniqueId(), cost);
                }
                return;
            }
            if (!spent) {
                NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                send(player, "<red>Not enough gold. Need <yellow>" + CurrencyFormat.formatGold(cost) + "<red>.");
                context.reopen(this);
                return;
            }
            deliverKit(context, player, service, listing, () -> economy.refundGold(player.getUniqueId(), cost), cost, "gold");
        }));
    }

    private void deliverKit(
            GuiClickContext context,
            Player player,
            CustomItemService service,
            BlackMarketConfigService.Listing listing,
            Runnable refund,
            long cost,
            String currencyLabel
    ) {
        List<ItemStack> kit = new ArrayList<>();
        for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
            kit.add(ExtractionCustomItemProvider.createInfuseArmor(service, piece, listing.rarity(), listing.armorSet()));
        }
        int freeSlots = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                freeSlots++;
            }
        }
        if (freeSlots < kit.size()) {
            refund.run();
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            send(player, "<red>Need at least " + kit.size() + " free inventory slots.");
            context.reopen(this);
            return;
        }
        for (ItemStack stack : kit) {
            player.getInventory().addItem(stack);
        }
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        send(player, "<green>Purchased <light_purple>" + listing.displayName() + "<green> for <yellow>"
                + CurrencyFormat.format(cost) + " " + currencyLabel + "<green>.");
        context.reopen(this);
    }

    private long costFor(BlackMarketConfigService.Listing listing) {
        return paymentMode == PaymentMode.COINS ? listing.coinCost() : listing.goldCost();
    }

    private long balanceFor(Player viewer, PaymentMode mode) {
        return mode == PaymentMode.COINS
                ? economy.cachedCoins(viewer.getUniqueId())
                : economy.cachedGold(viewer.getUniqueId());
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
