package network.skypvp.extraction.gameplay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.item.ExtractionStatKeys;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;

/**
 * Arc Raiders-style stamina pool for live breach raiders. The internal pool uses a 0–200+ scale (modules can raise
 * the cap); the vanilla food bar (0–20 drumsticks) is a proportional view of that pool. Drains while sprinting,
 * regenerates after an exhausted cooldown when fully depleted, and respects armor/module stats plus medic syringes.
 */
public final class BreachStaminaService {

    /** Internal stamina pool baseline (not the food-bar unit count). */
    public static final double BASE_MAX = 200.0D;
    public static final double BASE_REGEN_PER_SECOND = 10.0D;
    /** ~44s of continuous sprint on the 200 pool — raiding is run-heavy, so sprint stays cheap. */
    public static final double BASE_SPRINT_DRAIN_PER_SECOND = 4.5D;

    /** Vanilla food bar uses 0–20 drumsticks; stamina ratio maps onto this scale. */
    public static final int FOOD_BAR_UNITS = 20;

    /** Delay before passive regen begins after the pool is fully drained. */
    public static final long EXHAUSTED_REGEN_COOLDOWN_MS = 2_500L;

    private static final double EMPTY_THRESHOLD = 0.05D;

    private final PaperCorePlugin core;
    private final BreachConfigService config;
    private final double baseMax;
    private final double baseRegenPerSecond;
    private final double baseSprintDrainPerSecond;
    private final long exhaustedRegenCooldownMs;
    private final boolean persistOnReconnect;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, StaminaSnapshot> reconnectPending = new ConcurrentHashMap<>();
    private BreachStaminaSprintBridge sprintBridge;

    public BreachStaminaService(PaperCorePlugin core, BreachConfigService config) {
        this.core = core;
        this.config = config;
        this.baseMax = config == null ? BASE_MAX : config.staminaBaseMax();
        this.baseRegenPerSecond = config == null ? BASE_REGEN_PER_SECOND : config.staminaBaseRegenPerSecond();
        this.baseSprintDrainPerSecond = config == null ? BASE_SPRINT_DRAIN_PER_SECOND : config.staminaBaseSprintDrainPerSecond();
        this.exhaustedRegenCooldownMs = config == null ? EXHAUSTED_REGEN_COOLDOWN_MS : config.staminaExhaustedRegenCooldownMillis();
        this.persistOnReconnect = config == null || config.staminaPersistOnReconnect();
    }

    public void bindSprintBridge(BreachStaminaSprintBridge sprintBridge) {
        this.sprintBridge = sprintBridge;
    }

    public boolean hasStamina(Player player) {
        return current(player) > EMPTY_THRESHOLD;
    }

    public void enroll(Player player) {
        if (player == null) {
            return;
        }
        double max = maxFor(player);
        states.put(player.getUniqueId(), new State(max, max, 0L, 0L, 0L));
        syncFoodBar(player);
    }

    public void unenroll(UUID playerId) {
        if (playerId == null) {
            return;
        }
        states.remove(playerId);
        reconnectPending.remove(playerId);
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && core != null && core.platformScheduler() != null) {
            core.platformScheduler().runOnPlayer(player, () -> resetFoodBar(player));
        }
    }

    /** Saves stamina pool state before a mid-raid disconnect so reconnect can resume it. */
    public void captureForReconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        if (!persistOnReconnect) {
            states.remove(playerId);
            return;
        }
        State state = states.get(playerId);
        if (state != null) {
            reconnectPending.put(playerId, StaminaSnapshot.from(state));
        }
        states.remove(playerId);
    }

    /** Restores a captured stamina snapshot after reconnect enrollment. */
    public void restoreReconnectCapture(Player player) {
        if (player == null || !persistOnReconnect) {
            return;
        }
        StaminaSnapshot snapshot = reconnectPending.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        State state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        snapshot.applyTo(state, maxFor(player));
        syncFoodBar(player);
    }

    public void clearReconnectCapture(UUID playerId) {
        if (playerId != null) {
            reconnectPending.remove(playerId);
        }
    }

    /**
     * Vanilla refuses to START a sprint at food {@code <= 6} and force-stops an active one —
     * enforced INSIDE the client, which is the only sprint authority that can't be bypassed.
     */
    private static final int SPRINT_LOCK_FOOD_LEVEL = 6;

    /**
     * The food level doubles as the client-enforced sprint lock. Sprint is client-
     * authoritative: server-side {@code setSprinting(false)} and cancelled toggle events
     * clear OUR flag, but a client that keeps sprint-moving still gets sprint speed — which
     * is how players sprinted forever on an empty bar. Pinning food to
     * {@link #SPRINT_LOCK_FOOD_LEVEL} while sprint is disallowed makes the client itself
     * refuse to sprint, exactly like vanilla hunger. The pack renders the hunger row with
     * transparent sprites, so this is mechanically vanilla but visually invisible — the
     * player-facing stamina readout stays on the HUD bar.
     */
    public void syncFoodBar(Player player) {
        if (player == null || !isEnrolled(player.getUniqueId())) {
            return;
        }
        int targetFood = isSprintAllowed(player) ? FOOD_BAR_UNITS : SPRINT_LOCK_FOOD_LEVEL;
        if (player.getFoodLevel() != targetFood) {
            player.setFoodLevel(targetFood);
        }
        if (player.getSaturation() != 0.0F) {
            player.setSaturation(0.0F);
        }
        if (player.getExhaustion() != 0.0F) {
            player.setExhaustion(0.0F);
        }
        if (sprintBridge != null) {
            sprintBridge.applySprintGate(player, this);
        }
    }

    /** A drained pool must recover to this ratio before sprint can START again. */
    public static final double SPRINT_RESUME_RATIO = 0.20D;

    /**
     * Direct sprint gate with hysteresis: an active sprint may continue until the pool is
     * empty, but a new sprint only starts once recovered past {@link #SPRINT_RESUME_RATIO}.
     * This avoids the stutter-sprint oscillation right at the drain boundary.
     */
    public boolean isSprintAllowed(Player player) {
        double currentRatio = ratio(player);
        if (currentRatio <= 0.0D) {
            return false;
        }
        return player.isSprinting() || currentRatio >= SPRINT_RESUME_RATIO;
    }

    /**
     * Gate for a NEW sprint press ({@code PlayerToggleSprintEvent} start). Unlike
     * {@link #isSprintAllowed(Player)} it never grants the active-sprint grace — the event
     * fires before the server marks the player sprinting, so honoring {@code isSprinting()}
     * here would let toggle-key spam restart sprint on every key press with an empty pool.
     */
    public boolean canStartSprint(Player player) {
        return ratio(player) >= SPRINT_RESUME_RATIO;
    }

    private static void resetFoodBar(Player player) {
        player.setFoodLevel(FOOD_BAR_UNITS);
        player.setSaturation(0.0F);
        player.setExhaustion(0.0F);
    }

    public boolean isEnrolled(UUID playerId) {
        return playerId != null && states.containsKey(playerId);
    }

    public double current(Player player) {
        State state = stateOf(player);
        return state == null ? 0.0D : state.current;
    }

    public double max(Player player) {
        return maxFor(player);
    }

    public double ratio(Player player) {
        double max = maxFor(player);
        if (max <= 0.0D) {
            return 0.0D;
        }
        State state = stateOf(player);
        return state == null ? 0.0D : Math.max(0.0D, Math.min(1.0D, state.current / max));
    }

    public void tick(Player player, boolean sprinting, double deltaSeconds) {
        if (player == null || deltaSeconds <= 0.0D) {
            return;
        }
        State state = stateOf(player);
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        state.expireBuffs(now);
        double max = maxFor(player);
        state.current = Math.min(max, state.current);
        boolean hadStamina = state.current > EMPTY_THRESHOLD;

        if (state.drainFrozenUntil > now) {
            applyPassiveRegen(player, state, max, now, deltaSeconds);
            finishTick(player, state, hadStamina, now);
            return;
        }

        if (sprinting && state.current > EMPTY_THRESHOLD) {
            double drain = sprintDrainPerSecond(player) * deltaSeconds;
            if (state.regenBoostUntil > now) {
                drain *= 0.35D;
            }
            state.current = Math.max(0.0D, state.current - drain);
        } else {
            applyPassiveRegen(player, state, max, now, deltaSeconds);
        }
        finishTick(player, state, hadStamina, now);
    }

    private void applyPassiveRegen(Player player, State state, double max, long now, double deltaSeconds) {
        if (now < state.regenBlockedUntil) {
            return;
        }
        double regen = regenPerSecond(player) * deltaSeconds;
        if (state.regenBoostUntil > now) {
            regen *= 1.5D;
        }
        state.current = Math.min(max, state.current + regen);
    }

    private void finishTick(Player player, State state, boolean hadStamina, long now) {
        if (state.current <= EMPTY_THRESHOLD) {
            if (hadStamina) {
                state.regenBlockedUntil = now + exhaustedRegenCooldownMs;
            }
            state.current = 0.0D;
        }
        // Direct enforcement: no reliance on the vanilla food-level sprint block.
        if (player.isSprinting() && !isSprintAllowed(player)) {
            player.setSprinting(false);
            if (sprintBridge != null) {
                sprintBridge.recordSprintIntent(player, false);
            }
        }
        syncFoodBar(player);
    }

    public void restore(Player player, double amount) {
        if (player == null || amount <= 0.0D) {
            return;
        }
        State state = stateOf(player);
        if (state == null) {
            return;
        }
        double max = maxFor(player);
        state.current = Math.min(max, state.current + amount);
        if (state.current > EMPTY_THRESHOLD) {
            state.regenBlockedUntil = 0L;
        }
        syncFoodBar(player);
    }

    public void applyMedicSyringe(Player player, MedicConsumableType type) {
        if (player == null || type == null || !type.isSyringe()) {
            return;
        }
        if (stateOf(player) == null) {
            enroll(player);
        }
        applyBuff(
                player,
                ItemConfigOverrides.medicStaminaRestore(type),
                ItemConfigOverrides.medicRegenBoostMillis(type),
                ItemConfigOverrides.medicDrainFreezeMillis(type)
        );
        if (type == MedicConsumableType.OVERDRIVE_SERUM) {
            State state = stateOf(player);
            if (state != null) {
                long now = System.currentTimeMillis();
                long freeze = ItemConfigOverrides.medicDrainFreezeMillis(type);
                state.drainFrozenUntil = Math.max(state.drainFrozenUntil, now + freeze);
            }
        }
    }

    /** @deprecated use {@link #applyMedicSyringe(Player, MedicConsumableType)} */
    @Deprecated
    public void applyAdrenalineShot(Player player) {
        applyMedicSyringe(player, MedicConsumableType.ADRENALINE_SHOT);
    }

    /** @deprecated use {@link #applyMedicSyringe(Player, MedicConsumableType)} */
    @Deprecated
    public void applyStaminaStabilizer(Player player) {
        applyMedicSyringe(player, MedicConsumableType.STAMINA_STABILIZER);
    }

    /** @deprecated use {@link #applyMedicSyringe(Player, MedicConsumableType)} */
    @Deprecated
    public void applyOverdriveSerum(Player player) {
        applyMedicSyringe(player, MedicConsumableType.OVERDRIVE_SERUM);
    }

    private void applyBuff(Player player, double instant, long regenBoostMillis, long drainFreezeMillis) {
        State state = stateOf(player);
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (instant > 0.0D) {
            restore(player, instant);
        }
        if (regenBoostMillis > 0L) {
            state.regenBoostUntil = now + regenBoostMillis;
        }
        if (drainFreezeMillis > 0L) {
            state.drainFrozenUntil = now + drainFreezeMillis;
        }
        syncFoodBar(player);
    }

    private double maxFor(Player player) {
        double mult = 1.0D + namedStat(player, ExtractionStatKeys.STAMINA_MAX_MULT);
        return Math.max(25.0D, baseMax * mult);
    }

    private double regenPerSecond(Player player) {
        double mult = 1.0D + namedStat(player, ExtractionStatKeys.STAMINA_REGEN_MULT);
        return Math.max(1.0D, baseRegenPerSecond * mult);
    }

    private double sprintDrainPerSecond(Player player) {
        double mult = 1.0D + namedStat(player, ExtractionStatKeys.STAMINA_DRAIN_MULT);
        return Math.max(3.0D, baseSprintDrainPerSecond * (1.0D + mult));
    }

    private double namedStat(Player player, String key) {
        CustomItemService service = core == null ? null : core.customItemService();
        if (service == null || player == null || key == null) {
            return 0.0D;
        }
        return service.namedStat(player, key);
    }

    private State stateOf(Player player) {
        if (player == null) {
            return null;
        }
        return states.get(player.getUniqueId());
    }

    private static final class State {
        private double current;
        private long regenBoostUntil;
        private long drainFrozenUntil;
        private long regenBlockedUntil;

        private State(double max, double current, long regenBoostUntil, long drainFrozenUntil, long regenBlockedUntil) {
            this.current = current;
            this.regenBoostUntil = regenBoostUntil;
            this.drainFrozenUntil = drainFrozenUntil;
            this.regenBlockedUntil = regenBlockedUntil;
        }

        private void expireBuffs(long now) {
            if (regenBoostUntil > 0L && regenBoostUntil <= now) {
                regenBoostUntil = 0L;
            }
            if (drainFrozenUntil > 0L && drainFrozenUntil <= now) {
                drainFrozenUntil = 0L;
            }
        }
    }

    private record StaminaSnapshot(
            double current,
            long regenBoostUntil,
            long drainFrozenUntil,
            long regenBlockedUntil
    ) {
        private static StaminaSnapshot from(State state) {
            return new StaminaSnapshot(state.current, state.regenBoostUntil, state.drainFrozenUntil, state.regenBlockedUntil);
        }

        private void applyTo(State state, double max) {
            state.current = Math.max(0.0D, Math.min(max, current));
            state.regenBoostUntil = regenBoostUntil;
            state.drainFrozenUntil = drainFrozenUntil;
            state.regenBlockedUntil = regenBlockedUntil;
        }
    }
}
