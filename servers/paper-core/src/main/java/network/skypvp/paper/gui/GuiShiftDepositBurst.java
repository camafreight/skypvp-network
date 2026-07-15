package network.skypvp.paper.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Detects vanilla shift-double-click bursts (multiple {@code MOVE_TO_OTHER_INVENTORY} packets in one tick)
 * and schedules a one-time sweep for any remaining matching stacks.
 */
final class GuiShiftDepositBurst {

    private static final Map<UUID, BurstState> ACTIVE = new ConcurrentHashMap<>();

    private GuiShiftDepositBurst() {
    }

    /**
     * Records a shift insert and schedules {@code endOfTickSweep} once when the current server tick ends.
     * The sweep runs only when two or more shift inserts occurred in the same tick (vanilla gather-all).
     */
    static void track(Player player, GuiManager manager, Runnable endOfTickSweep) {
        if (player == null || manager == null || endOfTickSweep == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long tick = player.getWorld().getFullTime();
        BurstState state = ACTIVE.compute(playerId, (id, existing) -> {
            if (existing == null || existing.tick != tick) {
                return new BurstState(tick, 1);
            }
            existing.count++;
            return existing;
        });
        if (state == null) {
            return;
        }
        if (!state.scheduled) {
            state.scheduled = true;
            manager.runNextTick(player, () -> {
                BurstState finished = ACTIVE.remove(playerId);
                if (finished == null || finished.count < 2) {
                    return;
                }
                endOfTickSweep.run();
            });
        }
    }

    private static final class BurstState {
        private final long tick;
        private int count;
        private boolean scheduled;

        private BurstState(long tick, int count) {
            this.tick = tick;
            this.count = count;
        }
    }
}
