package network.skypvp.paper.inventory.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiClickInventory;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.library.ItemStackCodec;
import network.skypvp.paper.repository.ExtractionInventoryRepository;
import network.skypvp.paper.repository.PlayerCurrencyRepository;
import network.skypvp.paper.service.NetworkMenuService;
import network.skypvp.paper.service.PlayerInventoryManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class VaultGuiService {

    private final PaperCorePlugin plugin;
    private final ExtractionInventoryRepository repository;
    private final NetworkMenuService networkMenuService;
    private final GuiManager guiManager;
    private final AtomicLong saveSequence = new AtomicLong(System.currentTimeMillis());
    private final Map<UUID, VaultSession> pendingSessions = new ConcurrentHashMap<>();

    public VaultGuiService(
            PaperCorePlugin plugin,
            ExtractionInventoryRepository repository,
            NetworkMenuService networkMenuService,
            GuiManager guiManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.networkMenuService = networkMenuService;
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager");
    }

    public void open(Player player, boolean returnToNetworkMenu) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<Map<Integer, String>> cached = this.repository.getCachedContainer(
                playerId,
                PlayerInventoryManager.CONTAINER_VAULT
        );
        if (cached.isPresent()) {
            // Fully warm (containers preload at join; rows cache after the first load/purchase):
            // open synchronously on the caller's thread — no async hops, no perceptible delay.
            Optional<Integer> cachedRows = this.repository.getCachedVaultUnlockedRows(playerId);
            if (cachedRows.isPresent()) {
                this.openVaultInventory(player, cached.get(), cachedRows.get(), returnToNetworkMenu);
                return;
            }
            this.repository.loadVaultUnlockedRows(playerId).thenAcceptAsync(unlockedRows -> {
                this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (player.isOnline()) {
                        this.openVaultInventory(player, cached.get(), unlockedRows, returnToNetworkMenu);
                    }
                });
            }, runnable -> this.plugin.platformScheduler().runAsync(runnable));
            return;
        }
        player.sendActionBar(Component.text("Opening vault...", NamedTextColor.GRAY));
        CompletableFuture<Map<Integer, String>> slotsFuture = this.repository.loadContainer(
                playerId,
                PlayerInventoryManager.CONTAINER_VAULT
        );
        CompletableFuture<Integer> rowsFuture = this.repository.loadVaultUnlockedRows(playerId);
        CompletableFuture.allOf(slotsFuture, rowsFuture).thenAcceptAsync(ignored -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.sendActionBar(Component.empty());
                this.openVaultInventory(player, slotsFuture.join(), rowsFuture.join(), returnToNetworkMenu);
            });
        }, runnable -> this.plugin.platformScheduler().runAsync(runnable)).exceptionally(error -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(Component.empty());
                    NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not open your vault. Try again.</red>"));
                }
            });
            return null;
        });
    }

    private void openVaultInventory(
            Player player,
            Map<Integer, String> slots,
            int unlockedRows,
            boolean returnToNetworkMenu
    ) {
        VaultHolder holder = new VaultHolder(player.getUniqueId(), returnToNetworkMenu);
        holder.setUnlockedRows(unlockedRows);
        holder.replaceAll(this.decodeSlots(slots));
        holder.setPage(holder.preferredOpenPage());
        VaultMenu menu = new VaultMenu(holder, this, this.plugin.coreHotbarService());
        this.guiManager.open(player, menu);
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
    }

    public void changePage(Player player, VaultHolder holder, int delta) {
        if (player == null || holder == null || holder.getInventory() == null) {
            return;
        }
        VaultLayout.syncContentFromInventory(holder.getInventory(), holder);
        int nextPage = holder.page() + delta;
        if (nextPage < 0 || nextPage >= holder.totalPages()) {
            return;
        }
        holder.setPage(nextPage);
        // Re-open (GuiManager replaces in place, preserving history): the open-screen packet
        // is the only way to resend the title, and the title carries the scroll thumb glyph.
        // An in-place repaint would move the items but leave the thumb frozen. The
        // scroll-transition flag suppresses the replaced menu's close-time persist; open()
        // runs its close callbacks synchronously, so the flag window is exact.
        VaultMenu menu = new VaultMenu(holder, this, this.plugin.coreHotbarService());
        holder.beginScrollTransition();
        try {
            this.guiManager.open(player, menu);
        } finally {
            holder.endScrollTransition();
        }
    }

    /** Writes holder state to the vault cache and database. */
    public void persistHolder(VaultHolder holder) {
        if (holder == null || this.pendingSessions.containsKey(holder.playerId())) {
            return;
        }
        Inventory inventory = holder.getInventory();
        if (inventory != null) {
            VaultLayout.syncContentFromInventory(inventory, holder);
        }
        this.save(holder.playerId(), holder.snapshot());
    }

    public void handleBack(Player player, VaultHolder holder) {
        if (player == null || holder == null) {
            return;
        }
        persistHolder(holder);
        player.closeInventory();
        if (holder.returnToNetworkMenu() && this.networkMenuService != null) {
            this.plugin.platformScheduler().runOnPlayer(player, () -> this.networkMenuService.openRootMenu(player));
        }
    }

    public void promptRowPurchase(Player player, VaultHolder holder) {
        if (player == null || holder == null || holder.getInventory() == null) {
            return;
        }
        if (holder.purchasableRow() >= VaultSlotAccess.maxRows()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Your vault is fully expanded.</red>"));
            return;
        }
        VaultLayout.syncContentFromInventory(holder.getInventory(), holder);
        this.save(holder.playerId(), holder.snapshot());
        int row = holder.purchasableRow();
        long price = holder.purchasableRowPrice();
        this.pendingSessions.put(player.getUniqueId(), new VaultSession(holder.copyForSession(), holder.returnToNetworkMenu()));
        player.closeInventory();
        // Open on the next tick so the vault close finishes before GuiManager registers this menu.
        // Opening in the same tick as closeInventory leaves the confirm GUI unmanaged and its items lootable.
        this.plugin.platformScheduler().runOnPlayerLater(player, () -> {
            if (!player.isOnline() || !this.pendingSessions.containsKey(player.getUniqueId())) {
                return;
            }
            this.guiManager.open(player, this.wrapPurchaseConfirmMenu(this.buildPurchaseConfirmMenu(row, price)));
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        }, 1L);
    }

    private GuiMenu wrapPurchaseConfirmMenu(GuiMenu inner) {
        return new GuiMenu() {
            @Override
            public net.kyori.adventure.text.Component title() {
                return inner.title();
            }

            @Override
            public int size() {
                return inner.size();
            }

            @Override
            public void render(Player viewer, Inventory inventory) {
                inner.render(viewer, inventory);
            }

            @Override
            public void onClick(GuiClickContext context) {
                inner.onClick(context);
            }

            @Override
            public void onClose(Player viewer) {
                if (VaultGuiService.this.pendingSessions.containsKey(viewer.getUniqueId())) {
                    VaultGuiService.this.cancelRowPurchase(viewer);
                }
                inner.onClose(viewer);
            }
        };
    }

    private GuiMenu buildPurchaseConfirmMenu(int rowIndex, long price) {
        int slotStart = rowIndex * VaultLayout.SLOTS_PER_ROW + 1;
        int slotEnd = Math.min((rowIndex + 1) * VaultLayout.SLOTS_PER_ROW, VaultLayout.MAX_VAULT_SLOTS);
        Component title = MiniMessage.miniMessage().deserialize("<gold>Unlock Vault Row?</gold>");
        return GuiMenuBuilder.create(title, 27)
                .fill(this.confirmFillPane())
                .button(11, GuiButtonLibrary.positiveAction(Material.LIME_WOOL, "Confirm Purchase", lore -> lore
                        .fact("Row", String.valueOf(rowIndex + 1))
                        .fact("Cost", VaultRowPricing.formatCoins(price) + " coins")
                        .footerStrong("<#55FF55>", "Click to confirm")), ctx -> this.confirmRowPurchase(ctx.viewer()))
                .button(13, GuiButtonLibrary.infoCard(Material.GOLD_NUGGET, "Row " + (rowIndex + 1), lore -> lore
                        .plain("Adds 8 more stash slots")
                        .fact("Slots", slotStart + "-" + slotEnd)
                        .fact("Price", VaultRowPricing.formatCoins(price) + " coins")), ctx -> { })
                .button(15, GuiButtonLibrary.warningAction(Material.RED_WOOL, "Cancel", lore -> lore
                        .plain("Return to your vault without purchasing")
                        .footer("<#FF5555>", "Click to cancel")), ctx -> this.cancelRowPurchase(ctx.viewer()))
                .build();
    }

    private void confirmRowPurchase(Player player) {
        if (player == null) {
            return;
        }
        VaultSession session = this.pendingSessions.remove(player.getUniqueId());
        if (session == null) {
            this.guiManager.close(player);
            return;
        }
        VaultHolder holder = session.holder();
        int expectedRows = holder.unlockedRows();
        long price = VaultRowPricing.priceForRow(holder.purchasableRow());
        UUID playerId = player.getUniqueId();
        this.guiManager.close(player);
        player.sendActionBar(Component.text("Processing purchase...", NamedTextColor.GRAY));
        PlayerCurrencyRepository currencyRepository = this.plugin.playerCurrencyRepository();
        if (currencyRepository != null) {
            currencyRepository.ensurePlayer(playerId);
        }
        CompletableFuture<Boolean> spendFuture = currencyRepository == null
                ? CompletableFuture.completedFuture(false)
                : currencyRepository.trySpendCoins(playerId, price);
        spendFuture
                .thenCompose(spent -> {
                    if (!spent) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return this.repository.incrementVaultUnlockedRows(playerId, expectedRows);
                })
                .thenAcceptAsync(success -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendActionBar(Component.empty());
                    if (!success) {
                        NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                "<red>Could not unlock that row. Make sure you have "
                                        + VaultRowPricing.formatCoins(price) + " coins and try again.</red>"
                        ));
                        this.reopenVault(player, session);
                        return;
                    }
                    holder.setUnlockedRows(expectedRows + 1);
                    holder.setPage(VaultSlotAccess.pageForVaultIndex(expectedRows * VaultLayout.SLOTS_PER_ROW));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<green>Unlocked vault row " + (expectedRows + 1) + "!</green> <gray>(+8 slots)</gray>"
                    ));
                    this.reopenVault(player, new VaultSession(holder, session.returnToNetworkMenu()));
                }), runnable -> this.plugin.platformScheduler().runAsync(runnable))
                .exceptionally(error -> {
                    this.plugin.platformScheduler().runOnPlayer(player, () -> {
                        if (player.isOnline()) {
                            player.sendActionBar(Component.empty());
                            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Purchase failed. Try again.</red>"));
                            this.reopenVault(player, session);
                        }
                    });
                    return null;
                });
    }

    private void cancelRowPurchase(Player player) {
        if (player == null) {
            return;
        }
        VaultSession session = this.pendingSessions.remove(player.getUniqueId());
        this.guiManager.close(player);
        if (session != null) {
            this.reopenVault(player, session);
        }
    }

    private ItemStack confirmFillPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private void reopenVault(Player player, VaultSession session) {
        if (player == null || session == null) {
            return;
        }
        VaultHolder holder = session.holder();
        VaultMenu menu = new VaultMenu(holder, this, this.plugin.coreHotbarService());
        this.guiManager.open(player, menu);
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
    }

    public int depositShiftClickedStack(Player player, VaultHolder holder, ItemStack stack) {
        return depositStack(player, holder, stack, -1);
    }

    /**
     * Deposits into a specific vault slot when possible (drag target), otherwise uses smart placement.
     *
     * @return amount deposited
     */
    public int depositStack(Player player, VaultHolder holder, ItemStack stack, int preferredVaultIndex) {
        if (player == null || holder == null || stack == null || stack.getType().isAir()) {
            return 0;
        }
        if (holder.getInventory() != null) {
            VaultLayout.syncContentFromInventory(holder.getInventory(), holder);
        }
        int deposited = VaultDepositHelper.deposit(holder, stack.clone(), preferredVaultIndex);
        if (deposited <= 0) {
            if (holder.findFirstEmptyVaultSlot() < 0 && !hasMergeRoom(holder, stack)) {
                if (holder.purchasableRow() < VaultSlotAccess.maxRows()) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<red>Your unlocked vault space is full.</red> <gray>Purchase the next row to store more items.</gray>"
                    ));
                } else {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Your vault is completely full.</red>"));
                }
            }
            return 0;
        }
        repaintFromHolder(holder);
        this.guiManager.refresh(player);
        return deposited;
    }

    /**
     * Repaints the GUI from holder state after a direct holder mutation. Skipping
     * {@link VaultHolder#resetInventorySync()} here would let {@link VaultLayout#render} sync the
     * stale (pre-mutation) inventory back over the holder and erase the change — the "vanishing
     * deposit" bug.
     */
    private static void repaintFromHolder(VaultHolder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory != null) {
            holder.resetInventorySync();
            VaultLayout.render(inventory, holder);
        }
    }

    /** Sweeps remaining similar stacks from the player inventory after a shift-double-click burst. */
    public int depositAllMatchingFromInventory(Player player, VaultHolder holder, ItemStack reference) {
        if (player == null || holder == null || reference == null || reference.getType().isAir()) {
            return 0;
        }
        int total = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir() || !stack.isSimilar(reference)) {
                continue;
            }
            if (this.plugin.coreHotbarService() != null && this.plugin.coreHotbarService().isServerItem(stack)) {
                continue;
            }
            int deposited = depositStack(player, holder, stack, -1);
            if (deposited <= 0) {
                break;
            }
            int removed = GuiClickInventory.consumePlayerSlot(player, slot, deposited);
            total += removed;
            if (removed < deposited) {
                revertDeposit(holder, stack, deposited - removed);
            }
        }
        return total;
    }

    public void revertDeposit(VaultHolder holder, ItemStack reference, int amount) {
        if (holder == null || reference == null || amount <= 0) {
            return;
        }
        int remaining = amount;
        int limit = holder.depositableSlotLimit();
        for (int index = limit - 1; index >= 0 && remaining > 0; index--) {
            ItemStack stored = holder.get(index);
            if (stored == null || !stored.isSimilar(reference)) {
                continue;
            }
            int remove = Math.min(remaining, stored.getAmount());
            int left = stored.getAmount() - remove;
            if (left <= 0) {
                holder.remove(index);
            } else {
                ItemStack updated = stored.clone();
                updated.setAmount(left);
                holder.put(index, updated);
            }
            remaining -= remove;
        }
        // Repaint immediately: the deposit was already rendered into the GUI, and a later live-sync
        // would read it back into the holder, silently undoing this rollback (dupe).
        repaintFromHolder(holder);
    }

    private static boolean hasMergeRoom(VaultHolder holder, ItemStack stack) {
        int limit = holder.depositableSlotLimit();
        for (int index = 0; index < limit; index++) {
            ItemStack existing = holder.get(index);
            if (existing != null
                    && existing.isSimilar(stack)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public void scheduleInventoryResync(Player player) {
        if (player == null) {
            return;
        }
        this.plugin.platformScheduler().runOnPlayerLater(player, player::updateInventory, 1L);
    }

    /** Last persisted content checksum per player; unchanged snapshots never hit the DB. */
    private final Map<UUID, String> lastSavedChecksum = new java.util.concurrent.ConcurrentHashMap<>();

    private void save(UUID playerId, Map<Integer, ItemStack> items) {
        Map<Integer, String> encoded = new HashMap<>();
        items.forEach((index, item) -> {
            if (item != null && !item.getType().isAir()) {
                encoded.put(index, ItemStackCodec.encode(item));
            }
        });
        String checksum = this.checksum(encoded);
        // Redundant-write guard: repeated closes/back-navigation with identical content
        // produced overlapping bulk rewrites that deadlocked in the DB. Only dirty
        // snapshots are persisted.
        if (checksum.equals(this.lastSavedChecksum.get(playerId))) {
            return;
        }
        this.lastSavedChecksum.put(playerId, checksum);
        long revision = this.saveSequence.incrementAndGet();
        this.repository.saveContainerBulk(playerId, PlayerInventoryManager.CONTAINER_VAULT, encoded, revision, checksum);
    }

    private Map<Integer, ItemStack> decodeSlots(Map<Integer, String> slots) {
        Map<Integer, ItemStack> decoded = new HashMap<>();
        if (slots == null) {
            return decoded;
        }
        slots.forEach((index, payload) -> {
            try {
                ItemStack item = ItemStackCodec.decode(payload);
                if (item != null && !item.getType().isAir()) {
                    // Vault stacks are stored fully serialized; modernize custom items whose
                    // display material/model changed since they were deposited.
                    if (this.plugin.customItemService() != null) {
                        item = this.plugin.customItemService().reconcile(item);
                    }
                    decoded.put(index, item);
                }
            } catch (RuntimeException ignored) {
            }
        });
        return decoded;
    }

    private String checksum(Map<Integer, String> slots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            slots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    digest.update((entry.getKey() + ":" + entry.getValue()).getBytes(StandardCharsets.UTF_8))
            );
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            return "";
        }
    }

    private record VaultSession(VaultHolder holder, boolean returnToNetworkMenu) {
    }
}
