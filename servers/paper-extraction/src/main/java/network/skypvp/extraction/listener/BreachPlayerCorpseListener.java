package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseLayout;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService.BreachPlayerCorpseState;
import org.bukkit.Chunk;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/** Corpse world interaction and visibility (loot GUI handled by {@link network.skypvp.paper.gui.GuiManager}). */
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
