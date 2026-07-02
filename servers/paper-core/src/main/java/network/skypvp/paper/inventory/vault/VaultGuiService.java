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
        Inventory inventory = VaultLayout.createInventory(holder);
        player.openInventory(inventory);
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
        VaultLayout.render(holder.getInventory(), holder);
        player.updateInventory();
    }

    public void handleClose(Inventory inventory) {
        if (!(inventory.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        if (this.pendingSessions.containsKey(holder.playerId())) {
            return;
        }
        VaultLayout.syncContentFromInventory(inventory, holder);
        this.save(holder.playerId(), holder.snapshot());
    }

    public void handleBack(Player player, VaultHolder holder) {
        if (player == null || holder == null) {
            return;
        }
        if (holder.getInventory() != null) {
            VaultLayout.syncContentFromInventory(holder.getInventory(), holder);
            this.save(holder.playerId(), holder.snapshot());
        }
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
        Inventory inventory = VaultLayout.createInventory(holder);
        player.openInventory(inventory);
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
    }

    public boolean depositShiftClickedStack(Player player, VaultHolder holder, ItemStack stack) {
        if (player == null || holder == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        if (holder.getInventory() != null) {
            VaultLayout.syncContentFromInventory(holder.getInventory(), holder);
        }
        int targetSlot = holder.findFirstEmptyVaultSlot();
        if (targetSlot < 0) {
            if (holder.purchasableRow() < VaultSlotAccess.maxRows()) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<red>Your unlocked vault space is full.</red> <gray>Purchase the next row to store more items.</gray>"
                ));
            } else {
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Your vault is completely full.</red>"));
            }
            return false;
        }
        holder.put(targetSlot, stack.clone());
        holder.setPage(holder.pageForVaultIndex(targetSlot));
        VaultLayout.render(holder.getInventory(), holder);
        player.updateInventory();
        return true;
    }

    public void scheduleInventoryResync(Player player) {
        if (player == null) {
            return;
        }
        this.plugin.platformScheduler().runOnPlayerLater(player, player::updateInventory, 1L);
    }

    private void save(UUID playerId, Map<Integer, ItemStack> items) {
        Map<Integer, String> encoded = new HashMap<>();
        items.forEach((index, item) -> {
            if (item != null && !item.getType().isAir()) {
                encoded.put(index, ItemStackCodec.encode(item));
            }
        });
        long revision = this.saveSequence.incrementAndGet();
        String checksum = this.checksum(encoded);
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
