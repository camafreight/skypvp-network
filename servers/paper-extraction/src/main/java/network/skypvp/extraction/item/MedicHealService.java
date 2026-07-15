package network.skypvp.extraction.item;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.gameplay.BreachPlayerVitality;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.entity.Player;

/**
 * Gradual raid healing after a medic supply finishes its eat cast. Lower tiers deliver small chunks slowly;
 * higher tiers deliver larger chunks faster — never an instant full restore (except when a single chunk
 * finishes the remaining wound).
 */
public final class MedicHealService {

    private static final double EPSILON = 0.05D;

    public enum Outcome {
        STARTED,
        ALREADY_FULL,
        ALREADY_HEALING
    }

    private static final class HealState {
        private final double chunkAmount;
        private final long periodTicks;
        private double remaining;
        private PlatformTask task;

        private HealState(double remaining, double chunkAmount, long periodTicks) {
            this.remaining = remaining;
            this.chunkAmount = chunkAmount;
            this.periodTicks = periodTicks;
        }
    }

    private final PaperCorePlugin core;
    private final ServerPlatform platform;
    private final Map<UUID, HealState> active = new ConcurrentHashMap<>();

    public MedicHealService(PaperCorePlugin core, ServerPlatform platform) {
        this.core = Objects.requireNonNull(core, "core");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public boolean isHealing(UUID playerId) {
        return playerId != null && active.containsKey(playerId);
    }

    public Outcome beginHeal(Player player, MedicConsumableType type) {
        if (player == null || type == null || !type.isHealing()) {
            return Outcome.ALREADY_FULL;
        }
        if (active.containsKey(player.getUniqueId())) {
            return Outcome.ALREADY_HEALING;
        }
        double max = BreachPlayerVitality.RAID_MAX_HEALTH;
        double headroom = max - player.getHealth();
        if (headroom <= EPSILON) {
            return Outcome.ALREADY_FULL;
        }

        double total = Math.min(ItemConfigOverrides.medicHeal(type), headroom);
        double chunk = Math.max(0.5D, ItemConfigOverrides.medicHealChunk(type));
        long periodTicks = Math.max(2L, ItemConfigOverrides.medicHealChunkIntervalTicks(type));

        UUID playerId = player.getUniqueId();
        HealState state = new HealState(total, chunk, periodTicks);
        active.put(playerId, state);
        // First chunk lands immediately after the eat cast completes, then continues on the tier cadence.
        deliverChunk(playerId);
        if (!active.containsKey(playerId)) {
            return Outcome.STARTED;
        }
        PlatformTask task = platform.runOnPlayerTimer(
                player,
                () -> deliverChunk(playerId),
                periodTicks,
                periodTicks
        );
        state.task = task;
        refreshActionBar(player);
        return Outcome.STARTED;
    }

    private void deliverChunk(UUID playerId) {
        HealState state = active.get(playerId);
        if (state == null) {
            return;
        }
        Player player = core.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            cancel(playerId);
            return;
        }
        double max = BreachPlayerVitality.RAID_MAX_HEALTH;
        double headroom = max - player.getHealth();
        if (headroom <= EPSILON || state.remaining <= EPSILON) {
            cancel(playerId);
            refreshActionBar(player);
            return;
        }
        double deliver = Math.min(state.chunkAmount, Math.min(state.remaining, headroom));
        if (deliver > EPSILON) {
            player.setHealth(Math.min(max, player.getHealth() + deliver));
            state.remaining -= deliver;
            refreshActionBar(player);
        }
        if (state.remaining <= EPSILON || (max - player.getHealth()) <= EPSILON) {
            cancel(playerId);
            refreshActionBar(player);
        }
    }

    public void cancel(UUID playerId) {
        HealState state = active.remove(playerId);
        if (state != null && state.task != null) {
            state.task.cancel();
        }
    }

    public void shutdown() {
        active.values().forEach(state -> {
            if (state.task != null) {
                state.task.cancel();
            }
        });
        active.clear();
    }

    public String describe(Outcome outcome, MedicConsumableType type) {
        MedicConsumableType resolved = type == null ? MedicConsumableType.BANDAGE_RAG : type;
        double heal = ItemConfigOverrides.medicHeal(resolved);
        double chunk = ItemConfigOverrides.medicHealChunk(resolved);
        return switch (outcome) {
            case STARTED -> "<green>Healing <gray>(+" + format(heal) + " HP in " + format(chunk) + " HP bursts).";
            case ALREADY_FULL -> "<red>Already at full health.";
            case ALREADY_HEALING -> "<red>Already applying a medic supply — wait for it to finish.";
        };
    }

    private void refreshActionBar(Player player) {
        if (core.actionBarService() != null) {
            core.actionBarService().refreshPlayer(player);
        }
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
