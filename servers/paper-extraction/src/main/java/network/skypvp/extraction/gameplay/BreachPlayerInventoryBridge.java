package network.skypvp.extraction.gameplay;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.paper.service.PlayerInventoryManager;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachPlayerInventoryBridge {

    private final JavaPlugin plugin;
    private final PaperCorePlugin core;
    private final ConcurrentHashMap<UUID, Boolean> inRaid = new ConcurrentHashMap<>();

    public BreachPlayerInventoryBridge(JavaPlugin plugin, PaperCorePlugin core) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
    }

    public void onJoinRaid(Player player) {
        if (player == null || this.inventoryManager() == null) {
            return;
        }
        this.inRaid.put(player.getUniqueId(), true);
        PlayerInventoryManager pim = this.inventoryManager();
        pim.flushRaidInventorySave(player);
        pim.prepareForRaid(player, () -> this.isInRaid(player.getUniqueId())).thenAcceptAsync(ignored -> {
            this.core.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline() && this.isInRaid(player.getUniqueId())) {
                    this.hotbarService().ensureActiveRaidHotbar(player);
                }
            });
        }, runnable -> this.core.platformScheduler().runAsync(runnable));
    }

    public void onCancelPendingJoin(Player player) {
        if (player == null) {
            return;
        }
        this.inRaid.remove(player.getUniqueId());
        PlayerInventoryManager inventoryManager = this.inventoryManager();
        if (inventoryManager != null) {
            inventoryManager.cancelPendingRaidSaves(player.getUniqueId());
            inventoryManager.flushRaidInventorySave(player);
        }
        this.hotbarService().ensureNetworkItems(player);
    }

    public CompletableFuture<Void> onExtractSuccess(Player player) {
        if (player == null || this.inventoryManager() == null) {
            return CompletableFuture.completedFuture(null);
        }
        this.inRaid.remove(player.getUniqueId());
        PlayerInventoryManager inventoryManager = this.inventoryManager();
        inventoryManager.cancelPendingRaidSaves(player.getUniqueId());
        return inventoryManager.saveRaidInventory(player).thenAcceptAsync(ignored -> {
            this.core.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    this.ensureHubHotbar(player);
                }
            });
        }, runnable -> this.core.platformScheduler().runAsync(runnable));
    }

    public void onDeathInRaid(Player player) {
        if (player == null || this.inventoryManager() == null) {
            return;
        }
        this.inventoryManager().clearRaid(player);
    }

    public void applySpectatorHotbar(Player player) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        this.hotbarService().ensureSpectatorHotbar(player);
    }

    public void onLeaveRaid(Player player) {
        this.onAbandonRaid(player);
    }

    public void onAbandonRaid(Player player) {
        if (player == null) {
            return;
        }
        this.inRaid.remove(player.getUniqueId());
        if (this.inventoryManager() != null) {
            this.inventoryManager().clearRaid(player);
        }
    }

    public void onSpectatorExitRaid(Player player) {
        if (player == null) {
            return;
        }
        this.inRaid.remove(player.getUniqueId());
        this.restoreHubInventory(player);
    }

    public void onMatchEnd(Player player, boolean extracted) {
        if (player == null) {
            return;
        }
        this.inRaid.remove(player.getUniqueId());
        if (extracted) {
            return;
        }
        if (this.inventoryManager() != null) {
            this.inventoryManager().clearRaid(player);
        }
        this.restoreHubInventory(player);
    }

    public void restoreHubInventory(Player player) {
        PlayerInventoryManager inventoryManager = this.inventoryManager();
        if (inventoryManager != null) {
            inventoryManager.restoreCurrentInventory(player);
            return;
        }
        this.applyHubInventoryAfterExtract(player);
    }

    private void applyHubInventoryAfterExtract(Player player) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        this.hotbarService().ensureActiveRaidHotbar(player);
    }

    private void ensureHubHotbar(Player player) {
        if (player == null) {
            return;
        }
        this.hotbarService().ensureNetworkItems(player);
    }

    public boolean isInRaid(UUID playerId) {
        return playerId != null && this.inRaid.containsKey(playerId);
    }

    public PaperCorePlugin core() {
        return this.core;
    }

    private PlayerInventoryManager inventoryManager() {
        return this.core.playerInventoryManager();
    }

    private CoreHotbarService hotbarService() {
        return this.core.coreHotbarService();
    }
}
