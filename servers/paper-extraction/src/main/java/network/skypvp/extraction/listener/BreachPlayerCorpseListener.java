package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseHolder;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseLayout;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService.BreachPlayerCorpseState;
import org.bukkit.Chunk;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

public final class BreachPlayerCorpseListener implements Listener {

    private final BreachPlayerCorpseService corpseService;

    public BreachPlayerCorpseListener(BreachPlayerCorpseService corpseService) {
        this.corpseService = Objects.requireNonNull(corpseService, "corpseService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        if (!BreachPlayerCorpseService.PROP_TYPE.equals(
                interaction.getPersistentDataContainer().get(this.corpseService.propTypeKey(), PersistentDataType.STRING)
        )) {
            return;
        }
        String rawId = interaction.getPersistentDataContainer().get(this.corpseService.corpseIdKey(), PersistentDataType.STRING);
        if (rawId == null) {
            return;
        }
        BreachPlayerCorpseState state = this.corpseService.findByInteraction(interaction.getUniqueId());
        if (state == null || BreachPlayerCorpseLayout.isEmpty(state.loot())) {
            return;
        }
        event.setCancelled(true);
        this.corpseService.openLoot(event.getPlayer(), state);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BreachPlayerCorpseHolder)) {
            return;
        }
        // Withdrawing (including shift-click) is allowed; only block actions that would deposit items INTO the
        // corpse so it stays loot-only. The shared backing inventory keeps items conserved, so no duplication.
        if (isDepositIntoCorpse(event)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.updateInventory();
            }
        }
    }

    private static boolean isDepositIntoCorpse(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        InventoryAction action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            // Shift-click from the corpse (top) is a withdraw (allow); from the player inventory it pushes into
            // the corpse (deposit).
            return clicked == null || !clicked.equals(top);
        }
        if (clicked != null && clicked.equals(top)) {
            return switch (action) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> true;
                default -> false;
            };
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BreachPlayerCorpseHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesCorpse = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (touchesCorpse) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.updateInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BreachPlayerCorpseHolder holder)) {
            return;
        }
        BreachPlayerCorpseState state = this.corpseService.find(holder.corpseId());
        if (state == null) {
            return;
        }
        this.corpseService.syncClosedInventory(state, event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.corpseService.showCorpsesInWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.corpseService.hideCorpsesFrom(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        this.corpseService.showCorpsesInWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        this.corpseService.resyncCorpsesInChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        Chunk fromChunk = event.getFrom().getChunk();
        Chunk toChunk = event.getTo().getChunk();
        if (fromChunk.getX() == toChunk.getX() && fromChunk.getZ() == toChunk.getZ()) {
            return;
        }
        this.corpseService.hideCorpsesInChunk(event.getPlayer(), fromChunk);
        this.corpseService.showCorpsesInWorld(event.getPlayer());
    }
}
