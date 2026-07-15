package network.skypvp.paper.questdialogue;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import java.util.Objects;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * W/S navigation, mouse-wheel choice scroll ({@link PlayerItemHeldEvent}), Shift confirm,
 * and Q abort for {@link QuestDialogueService}.
 */
public final class QuestDialogueInputListener implements Listener {

    private final QuestDialogueService dialogueService;
    private final PaperCorePlugin core;
    private PacketListenerAbstract packetListener;

    public QuestDialogueInputListener(QuestDialogueService dialogueService, PaperCorePlugin core) {
        this.dialogueService = Objects.requireNonNull(dialogueService, "dialogueService");
        this.core = Objects.requireNonNull(core, "core");
    }

    /** Registers PacketEvents DROP handling for empty-hand Q (call once after construction). */
    public void startPacketListener() {
        if (packetListener != null || !PacketEventsBridge.isAvailable()) {
            return;
        }
        packetListener = new PacketListenerAbstract(PacketListenerPriority.HIGH) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) {
                    return;
                }
                Object playerObj = event.getPlayer();
                if (!(playerObj instanceof Player player)) {
                    return;
                }
                if (!dialogueService.isInDialogue(player.getUniqueId())) {
                    return;
                }
                try {
                    WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
                    DiggingAction action = digging.getAction();
                    if (action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK) {
                        event.setCancelled(true);
                        abortDialogue(player);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
    }

    public void shutdown() {
        if (packetListener != null && PacketEventsBridge.isAvailable()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (RuntimeException ignored) {
            }
            packetListener = null;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!dialogueService.isInDialogue(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        if (!dialogueService.isInDialogue(player.getUniqueId())) {
            return;
        }
        var input = player.getCurrentInput();
        if (input.isForward()) {
            dialogueService.handleMoveSelection(player, -1);
        } else if (input.isBackward()) {
            dialogueService.handleMoveSelection(player, 1);
        }
        dialogueService.tick(player);
    }

    /**
     * Mouse wheel (no inventory open) arrives as a hotbar slot change. Cancel the slot swap
     * and treat adjacent notches as choice / speech navigation — same deltas as W/S.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHotbarScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!dialogueService.isInDialogue(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        int delta = scrollDelta(event.getPreviousSlot(), event.getNewSlot());
        if (delta == 0) {
            return;
        }
        dialogueService.handleMoveSelection(player, delta);
    }

    /**
     * Hotbar is 0..8. Wheel down usually increases the slot; wheel up decreases (wraps).
     * Number-key jumps use the shortest wrap direction so they still nudge selection.
     */
    private static int scrollDelta(int previousSlot, int newSlot) {
        int forward = Math.floorMod(newSlot - previousSlot, 9);
        if (forward == 0) {
            return 0;
        }
        int backward = 9 - forward;
        // Match W/S: forward (up list) = -1, backward (down list) = +1.
        // Wheel-up (slot decreases) → backward=1 → delta -1; wheel-down → forward=1 → delta +1.
        if (forward <= backward) {
            return 1;
        }
        return -1;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !dialogueService.isInDialogue(event.getPlayer().getUniqueId())) {
            return;
        }
        dialogueService.handleShift(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!dialogueService.isInDialogue(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        abortDialogue(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dialogueService.cancel(event.getPlayer());
    }

    private void abortDialogue(Player player) {
        // Folia-safe: hop onto the player's region if we arrived from a netty thread.
        if (core.platformScheduler() != null) {
            core.platformScheduler().runOnPlayer(player, () -> {
                if (dialogueService.isInDialogue(player.getUniqueId())) {
                    dialogueService.cancel(player);
                }
            });
            return;
        }
        if (dialogueService.isInDialogue(player.getUniqueId())) {
            dialogueService.cancel(player);
        }
    }
}
