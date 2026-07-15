package network.skypvp.extraction.backpack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import network.skypvp.extraction.item.BackpackDefinition;
import network.skypvp.extraction.item.BackpackPayload;
import network.skypvp.extraction.item.BackpackSkins;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.Event.Result;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import network.skypvp.paper.gui.PlayerInventoryCanvas;

/**
 * Raid backpack worn in the offhand slot. Pressing the swap-hands key opens the scrollable
 * vault-style GUI ({@link BackpackMenu}): pack storage on top, the player's real inventory
 * below — so loot can be shift-clicked or dragged in without hiding what they're carrying.
 * A skins button in the header opens the {@link BackpackSkinMenu} cosmetic browser.
 *
 * <p>The offhand slot is reserved ({@code NO BACKPACK} placeholder when empty). Contents persist
 * in the backpack item's custom-item payload (PDC / item NBT via {@link CustomItemService}) so
 * they survive transfers, trades, and death while the pack is closed. The display item is inert
 * {@link Material#PAPER} with a custom model (not a usable {@link Material#BUNDLE}) so wearing
 * a pack does not steal right-click from main-hand weapons.
 */
public final class BackpackService implements Listener {

    private static final int OFFHAND_SLOT = 40;

    private final PaperCorePlugin core;
    private final Plugin plugin;
    private final NamespacedKey fillerKey;
    private final NamespacedKey placeholderKey;

    public BackpackService(PaperCorePlugin core, Plugin plugin) {
        this.core = Objects.requireNonNull(core, "core");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.fillerKey = new NamespacedKey(plugin, "backpack_filler");
        this.placeholderKey = new NamespacedKey(plugin, "backpack_placeholder");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        GuiManager guiManager = core.guiManager();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            core.platformScheduler().runOnPlayer(player, () -> {
                if (guiManager != null && guiManager.activeMenu(player) instanceof BackpackMenu) {
                    guiManager.close(player);
                } else {
                    forceClose(player, true);
                }
            });
        }
    }

    // --- Item identification -------------------------------------------------------------------

    public boolean isBackpack(ItemStack stack) {
        CustomItemService items = core.customItemService();
        return items != null && stack != null && !stack.getType().isAir()
                && items.resolve(stack).map(instance -> BackpackDefinition.TYPE_ID.equals(instance.typeId())).orElse(false);
    }

    private BackpackPayload payloadOf(ItemStack stack) {
        CustomItemService items = core.customItemService();
        return items == null ? BackpackPayload.empty(1)
                : items.resolve(stack)
                .map(instance -> BackpackPayload.decode(instance.payloadCopy()))
                .orElse(BackpackPayload.empty(1));
    }

    private boolean isFiller(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(fillerKey, PersistentDataType.BYTE);
    }

    public boolean isPlaceholder(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE);
    }

    /**
     * Static check for the reserved offhand {@code NO BACKPACK} dye — used by corpse loot capture
     * (which does not hold a {@link BackpackService} reference).
     */
    public static boolean isPlaceholderItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        Plugin owner = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(BackpackService.class);
        NamespacedKey key = new NamespacedKey(owner, "backpack_placeholder");
        return stack.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private ItemStack createPlaceholder() {
        ItemStack placeholder = new ItemStack(Material.GRAY_DYE);
        placeholder.editMeta(meta -> {
            meta.displayName(ExtractionTexts.miniMessageTemplate(
                    "<!italic><gray><bold>NO BACKPACK</bold></gray>", ExtractionTexts.defaultLocale()));
            meta.lore(List.of(
                    ExtractionTexts.miniMessageTemplate("<!italic><dark_gray>This slot is reserved for a raid backpack.</dark_gray>", ExtractionTexts.defaultLocale()),
                    ExtractionTexts.miniMessageTemplate("<!italic><gray>Find one in the breach or craft it at</gray>", ExtractionTexts.defaultLocale()),
                    ExtractionTexts.miniMessageTemplate("<!italic><gray>the armory workbench.</gray>", ExtractionTexts.defaultLocale()),
                    ExtractionTexts.miniMessageTemplate("<!italic><gold>play.skyclub.gg</gold>", ExtractionTexts.defaultLocale())
            ));
            meta.setItemModel(new NamespacedKey("skypvp", "backpack_none"));
            meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
        });
        return placeholder;
    }

    // --- Offhand slot management -----------------------------------------------------------------

    /** Reserves the offhand slot: evicts foreign items to the main grid, seats the placeholder. */
    private void ensureOffhand(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isBackpack(offhand) || isPlaceholder(offhand)) {
            return;
        }
        if (!offhand.getType().isAir()) {
            player.getInventory().setItemInOffHand(null);
            player.getInventory().addItem(offhand).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        player.getInventory().setItemInOffHand(createPlaceholder());
    }

    /** Equips {@code backpack} from the main hand into the reserved offhand slot. */
    private void equip(Player player, EquipmentSlot hand) {
        ItemStack backpack = player.getInventory().getItem(hand);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isBackpack(offhand)) {
            send(player, "<yellow>You already wear a backpack — unequip it in your inventory first.");
            return;
        }
        player.getInventory().setItem(hand, null);
        player.getInventory().setItemInOffHand(backpack);
        send(player, "<green>Backpack equipped. Press <yellow>[F]</yellow> to open it.");
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9F, 1.0F);
    }

    // --- Backpack GUI (GuiManager / GuiLootContainerMenu) ---------------------------------------

    public boolean isViewOpen(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return isBackpack(offhand) && payloadOf(offhand).viewOpen();
    }

    boolean isBackpackMenuOpen(Player player) {
        GuiManager guiManager = core.guiManager();
        return guiManager != null && guiManager.activeMenu(player) instanceof BackpackMenu;
    }

    private void toggleView(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!isBackpack(offhand)) {
            return;
        }
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            send(player, "<red>Backpack UI is unavailable right now.");
            return;
        }
        if (payloadOf(offhand).viewOpen()) {
            if (isBackpackMenuOpen(player)) {
                guiManager.close(player);
            } else {
                forceClose(player, false);
            }
        } else {
            openView(player, guiManager);
        }
    }

    private void openView(Player player, GuiManager guiManager) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null || !isBackpack(offhand) || payloadOf(offhand).viewOpen()) {
            return;
        }
        BackpackPayload payload = payloadOf(offhand);
        if (!markViewOpen(player, payload)) {
            send(player, "<red>This backpack is unreadable.");
            return;
        }
        BackpackViewState state = new BackpackViewState(
                payload.tier(), payload.skin(), payload.capacity(), payload.contents());
        guiManager.open(player, new BackpackMenu(this, player, state));
        send(player, "<gray>Backpack opened — shift-click loot from your inventory below, or press <yellow>[F]</yellow> to stow.");
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 1.0F, 0.9F);
    }

    /** Scroll one row: re-open a fresh menu on the shared state so the title resends the thumb. */
    void scroll(Player player, BackpackMenu menu, int delta) {
        GuiManager guiManager = core.guiManager();
        BackpackViewState state = menu.state();
        if (guiManager == null) {
            return;
        }
        int next = state.scrollRow() + delta;
        if (next < 0 || next > state.maxScrollRow()) {
            return;
        }
        menu.syncFromInventory();
        state.setScrollRow(next);
        // Re-open (GuiManager replaces in place): the open-screen packet is the only way to
        // resend the title, and the title carries the scroll thumb glyph. The transition
        // flag suppresses the replaced menu's close-time persist; open() runs its close
        // callbacks synchronously, so the flag window is exact.
        state.beginScrollTransition();
        try {
            guiManager.open(player, new BackpackMenu(this, player, state));
        } finally {
            state.endScrollTransition();
        }
    }

    /** Replaces the pack view with the skin browser; the pack persists silently on the way out. */
    void openSkinMenu(Player player, BackpackMenu menu) {
        GuiManager guiManager = core.guiManager();
        if (guiManager == null) {
            return;
        }
        menu.syncFromInventory();
        menu.state().markSilentClose();
        guiManager.open(player, new BackpackSkinMenu(this, player, menu.state().tier()));
    }

    /** Back button of the skin browser — the pack was persisted+closed when the browser opened. */
    void reopenFromSkinMenu(Player player) {
        GuiManager guiManager = core.guiManager();
        if (guiManager != null) {
            openView(player, guiManager);
        }
    }

    String equippedSkin(Player player) {
        return payloadOf(player.getInventory().getItemInOffHand()).skin();
    }

    /** Applies an unlocked cosmetic skin to the worn backpack: payload choice + item model. */
    boolean applySkin(Player player, String skinId) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null || !isBackpack(offhand) || !BackpackSkins.isUnlocked(player, skinId)) {
            return false;
        }
        BackpackPayload payload = payloadOf(offhand).withSkin(skinId);
        ItemStack updated = items.updatePayload(offhand, ignored -> payload.encode());
        if (updated == null) {
            return false;
        }
        updated.editMeta(meta -> meta.setItemModel(BackpackSkins.modelKey(payload.tier(), payload.skin())));
        clearBundleContents(updated);
        player.getInventory().setItemInOffHand(updated);
        send(player, "<green>Backpack skin applied: <white>"
                + BackpackSkins.byId(skinId).displayName() + "</white>.");
        return true;
    }

    /** Real menu close — writes the view state into the worn item payload, then announces. */
    void handleMenuClosed(Player player, BackpackViewState state) {
        persistFromState(player, state);
        afterMenuClosed(player, state.silentClose());
    }

    /** Writes the synced view state into the worn backpack item payload. */
    void persistFromState(Player player, BackpackViewState state) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null || !isBackpack(offhand)) {
            return;
        }
        BackpackPayload payload = payloadOf(offhand);
        migrateLegacyStash(player, payload);
        List<ItemStack> safeContents = sanitizeContents(player, state.contentsList());
        BackpackPayload stored = new BackpackPayload(
                payload.tier(), false, payload.skin(), safeContents, List.of());
        applyPayload(player, stored);
    }

    /** Feedback after the GUI library finishes closing the menu. */
    void afterMenuClosed(Player player, boolean silent) {
        if (silent) {
            return;
        }
        BackpackPayload payload = payloadOf(player.getInventory().getItemInOffHand());
        int stacks = (int) payload.contents().stream()
                .filter(item -> item != null && !item.getType().isAir())
                .count();
        send(player, "<gray>Backpack stowed — <white>" + stacks + "</white> stack"
                + (stacks == 1 ? "" : "s") + " inside.");
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_DROP_CONTENTS, 1.0F, 1.1F);
    }

    private void forceClose(Player player, boolean silent) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null || !isBackpack(offhand)) {
            return;
        }
        BackpackPayload payload = payloadOf(offhand);
        if (!payload.viewOpen()) {
            return;
        }
        migrateLegacyStash(player, payload);
        applyPayload(player, payload.withView(false, List.of()));
        if (!silent) {
            afterMenuClosed(player, false);
        }
    }

    private boolean markViewOpen(Player player, BackpackPayload payload) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null) {
            return false;
        }
        BackpackPayload open = payload.withView(true, List.of());
        ItemStack updated = items.updatePayload(offhand, ignored -> open.encode());
        if (updated == null) {
            return false;
        }
        clearBundleContents(updated);
        player.getInventory().setItemInOffHand(updated);
        return true;
    }

    private void applyPayload(Player player, BackpackPayload payload) {
        CustomItemService items = core.customItemService();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (items == null || !isBackpack(offhand)) {
            return;
        }
        ItemStack updated = items.updatePayload(offhand, ignored -> payload.encode());
        if (updated != null) {
            clearBundleContents(updated);
            player.getInventory().setItemInOffHand(updated);
        }
    }

    /**
     * Legacy scrub for packs that were still {@link Material#BUNDLE} before the paper migration.
     * No-op once the stack is inert paper.
     */
    private static void clearBundleContents(ItemStack backpack) {
        if (backpack == null || !(backpack.getItemMeta() instanceof BundleMeta bundleMeta)) {
            return;
        }
        if (!bundleMeta.hasItems()) {
            return;
        }
        bundleMeta.setItems(List.of());
        backpack.setItemMeta(bundleMeta);
    }

    private List<ItemStack> sanitizeContents(Player player, List<ItemStack> raw) {
        List<ItemStack> safe = new ArrayList<>();
        for (ItemStack item : raw) {
            if (isBackpack(item)) {
                if (item != null && !item.getType().isAir()) {
                    player.getInventory().addItem(item).values().forEach(leftover ->
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                }
                safe.add(null);
            } else if (item == null || item.getType().isAir()) {
                safe.add(null);
            } else {
                safe.add(item.clone());
            }
        }
        return safe;
    }

    /** Canvas-era saves stashed the real main grid — put it back once on close. */
    private void migrateLegacyStash(Player player, BackpackPayload payload) {
        if (payload.stash().isEmpty()) {
            return;
        }
        PlayerInventoryCanvas.restoreMain(player, payload.stash().toArray(new ItemStack[0]));
    }

    // --- Give / command support ------------------------------------------------------------------

    /** Hands a freshly minted tier-N backpack to the player (used by /breachitems givebackpack). */
    public boolean give(Player player, int tier, java.util.function.Function<Integer, ItemStack> factory) {
        ItemStack backpack = factory.apply(Math.max(1, Math.min(BackpackDefinition.MAX_TIER, tier)));
        if (backpack == null) {
            return false;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!isBackpack(offhand)) {
            if (isPlaceholder(offhand)) {
                player.getInventory().setItemInOffHand(backpack);
            } else {
                ensureOffhand(player);
                player.getInventory().setItemInOffHand(backpack);
            }
            send(player, "<green>Backpack equipped to your pack slot. Press <yellow>[F]</yellow> to open it.");
        } else {
            player.getInventory().addItem(backpack).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            send(player, "<green>Backpack added to your inventory — right-click it to equip.");
        }
        return true;
    }

    // --- Events ------------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        core.platformScheduler().runOnPlayerLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            forceClose(player, true);
            ensureOffhand(player);
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (isBackpack(offhand)) {
                clearBundleContents(offhand);
                player.getInventory().setItemInOffHand(offhand);
            }
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forceClose(event.getPlayer(), true);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        GuiManager guiManager = core.guiManager();
        core.platformScheduler().runOnPlayer(player, () -> {
            if (guiManager != null && isBackpackMenuOpen(player)) {
                guiManager.close(player);
            } else {
                forceClose(player, true);
            }
            ensureOffhand(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!isViewOpen(player) && !isBackpackMenuOpen(player)) {
            return;
        }
        GuiManager guiManager = core.guiManager();
        core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (guiManager != null && isBackpackMenuOpen(player)) {
                guiManager.close(player);
            } else {
                forceClose(player, false);
            }
            send(player, "<red>Your backpack snapped shut!");
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GuiManager guiManager = core.guiManager();
        if (guiManager != null && guiManager.activeMenu(player) instanceof BackpackMenu menu) {
            menu.syncFromInventory();
            persistFromState(player, menu.state());
        } else {
            forceClose(player, true);
        }
        Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack drop = iterator.next();
            if (isFiller(drop) || isPlaceholder(drop)) {
                iterator.remove();
            }
        }
        core.platformScheduler().runOnPlayerLater(player, () -> {
            if (player.isOnline()) {
                ensureOffhand(player);
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        core.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
                toggleView(player);
            }
        });
    }

    /**
     * Equip from main-hand right-click. Offhand pack must never consume the click — especially
     * leftover {@link Material#BUNDLE} packs before presentation reconcile migrates them to paper.
     * Never {@link PlayerInteractEvent#setCancelled(true)} on offhand: that fights WeaponMechanics.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack used = event.getItem();
        if (event.getHand() == EquipmentSlot.HAND && isBackpack(used)) {
            denyItemUse(event);
            equip(player, EquipmentSlot.HAND);
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND && (isBackpack(used) || isPlaceholder(used))) {
            // Soft deny only — keep the interact chain intact for main-hand shooting.
            event.setUseItemInHand(Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack used = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        // Offhand pack must never dump when the main hand talks to an NPC / opens a menu.
        if (event.getHand() == EquipmentSlot.OFF_HAND && (isBackpack(used) || isPlaceholder(used))) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND && isBackpack(used)) {
            event.setCancelled(true);
            equip(event.getPlayer(), EquipmentSlot.HAND);
        }
    }

    private static void denyItemUse(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (isFiller(dropped) || isPlaceholder(dropped)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.CRAFTING
                && (event.getSlotType() == InventoryType.SlotType.CRAFTING || event.getSlotType() == InventoryType.SlotType.RESULT)) {
            event.setCancelled(true);
            return;
        }
        if (isFiller(event.getCurrentItem()) || isFiller(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (isFiller(hotbarItem)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND
                && (isViewOpen(player) || isBackpackMenuOpen(player))) {
            event.setCancelled(true);
            core.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    toggleView(player);
                }
            });
            return;
        }

        ItemStack inSlot = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();

        // Block vanilla bundle extract / insert — BundleMeta is empty, but never allow stuffing.
        if (isVanillaBundleClick(event.getClick()) && (isBackpack(inSlot) || isBackpack(cursor))) {
            event.setCancelled(true);
            return;
        }
        if (!cursorEmpty && isBackpack(inSlot)) {
            event.setCancelled(true);
            return;
        }

        boolean offhandClick = event.getClickedInventory() != null
                && event.getClickedInventory().getType() == InventoryType.PLAYER
                && event.getSlot() == OFFHAND_SLOT;
        if (!offhandClick) {
            return;
        }
        if (isBackpack(inSlot) && cursorEmpty && !payloadOf(inSlot).viewOpen()
                && event.getClick() == ClickType.LEFT) {
            // Left-click unequip only — right-click is blocked above as a bundle extract.
            core.platformScheduler().runOnPlayerLater(player, () -> {
                if (player.isOnline()) {
                    ensureOffhand(player);
                }
            }, 1L);
            return;
        }
        if (isBackpack(cursor) && (isPlaceholder(inSlot) || inSlot == null || inSlot.getType().isAir())) {
            event.setCancelled(true);
            player.getInventory().setItemInOffHand(cursor.clone());
            event.getView().setCursor(null);
            send(player, "<green>Backpack equipped. Press <yellow>[F]</yellow> to open it.");
            return;
        }
        event.setCancelled(true);
    }

    private static boolean isVanillaBundleClick(ClickType click) {
        return click == ClickType.RIGHT
                || click == ClickType.SHIFT_RIGHT
                || click == ClickType.DOUBLE_CLICK;
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
