package network.skypvp.paper.quest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import network.skypvp.paper.ai.navigation.MobNavigationSupport;
import network.skypvp.paper.ai.navigation.MobTerrainSupport;
import network.skypvp.paper.ai.navigation.NavigatingMobContext;
import network.skypvp.paper.ai.navigation.NavigationTracker;
import network.skypvp.paper.ai.statetree.StateClockContext;
import network.skypvp.paper.ai.statetree.StateTreeEngine;
import network.skypvp.paper.ai.statetree.StateTreeNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Runtime brain for one quest NPC: a {@link StateTreeEngine} over {@link QuestNpcState}
 * driving a real pathfinding mob through a daily routine — commute to a reserved POI slot
 * when the schedule opens, dwell/stroll at the post, walk home when it closes.
 *
 * <p>Ticked exclusively on the entity's owning region thread (via {@code runAtEntityTimer}),
 * so all entity access in here is thread-safe by construction.
 */
public final class QuestNpcAgent implements NavigatingMobContext, StateClockContext {

    /** Agent tick period in game ticks (service timer). */
    public static final long TICK_PERIOD = 5L;
    private static final long NAV_REFRESH_TICKS = 20L;
    private static final double ARRIVE_SQ = 4.0D;
    private static final int MAX_STALLS_BEFORE_TELEPORT = 3;
    private static final long DWELL_MIN_TICKS = 300L;
    private static final long DWELL_MAX_TICKS = 900L;
    private static final long STROLL_TIMEOUT_TICKS = 300L;
    /** Chance to switch to a different POI (when several are assigned) after a dwell expires. */
    private static final double POI_SWITCH_CHANCE = 0.35D;
    private static final double GREET_RADIUS = 4.0D;
    private static final long GREET_COOLDOWN_MS = 60_000L;
    private static final double FACE_RADIUS = 6.0D;
    /** How long the NPC keeps its last player-facing after the area empties before resetting. */
    private static final long FACE_LINGER_TICKS = 40L;
    /** Backoff before re-attempting a commute after no POI slot could be reserved. */
    private static final long COMMUTE_RETRY_TICKS = 600L;

    private final QuestNpcService service;
    private final QuestNpcProfile profile;
    private final Mob mob;
    private final NavigationTracker navigation = new NavigationTracker();
    private final StateTreeEngine<QuestNpcState, QuestNpcAgent> engine;
    private final Map<UUID, Long> greetedAtMs = new ConcurrentHashMap<>();

    private long tickCounter;
    private long stateEnteredTick;
    private long nextNavTick;
    private long dwellUntilTick;
    private long nextCommuteRetryTick;
    private boolean warnedCommuteBlocked;
    private long faceHoldUntilTick;
    private boolean facingPlayer;
    private int stallCount;
    private QuestLocationRegistry.Slot slot;
    private Location target;
    private Location strollGoal;

    QuestNpcAgent(QuestNpcService service, QuestNpcProfile profile, Mob mob) {
        this.service = service;
        this.profile = profile;
        this.mob = mob;
        this.engine = new StateTreeEngine<>(QuestNpcState.class, QuestNpcState.OFF_DUTY);
        registerNodes();
        engine.start(this);
    }

    // --- Context plumbing ------------------------------------------------------------------

    @Override
    public LivingEntity agentEntity() {
        return mob;
    }

    @Override
    public Mob agentMob() {
        return mob;
    }

    @Override
    public NavigationTracker navigation() {
        return navigation;
    }

    @Override
    public long navClock() {
        // Per-agent counter: on Folia, world time is region-local and jumps across merges.
        return tickCounter;
    }

    @Override
    public long currentWorldTick() {
        return tickCounter;
    }

    @Override
    public void onStateEntered(long tick) {
        this.stateEnteredTick = tick;
    }

    public QuestNpcProfile profile() {
        return profile;
    }

    public QuestNpcState state() {
        return engine.currentState();
    }

    public Mob entity() {
        return mob;
    }

    /** Where the NPC currently stands — live target for waypoint beacons. */
    public Location currentLocation() {
        return mob.getLocation();
    }

    /** Human-readable slot key for /quest npc info, or "-" when idle. */
    public String slotLabel() {
        return slot == null ? "-" : slot.key();
    }

    // --- Ticking ---------------------------------------------------------------------------

    /** Entry point from the entity-pinned timer; runs every {@link #TICK_PERIOD} ticks. */
    void tick() {
        tickCounter += TICK_PERIOD;
        if (!mob.isValid() || mob.isDead()) {
            return;
        }
        // Villagers re-register brain walk targets; re-strip every tick so state-tree owns movement.
        QuestNpcAiSupport.stripVanillaAi(mob);
        // Keep the beacon post fresh while wandering (entity thread; recordPost ignores sub-block jitter).
        service.recordPost(profile.key(), mob.getLocation());
        if (profile.paused) {
            mob.getPathfinder().stopPathfinding();
            return;
        }
        engine.tick(this);
    }

    /** Called by the service when the profile changed (POIs, schedule, home) — restart routine. */
    void resetRoutine() {
        releaseSlot();
        engine.forceState(QuestNpcState.RETURN_HOME, this);
    }

    void shutdown() {
        releaseSlot();
        mob.getPathfinder().stopPathfinding();
    }

    // --- State tree ------------------------------------------------------------------------

    private void registerNodes() {
        engine.register(QuestNpcState.OFF_DUTY, StateTreeNodes.of(
                ctx -> {
                    mob.getPathfinder().stopPathfinding();
                    MobNavigationSupport.resetProgress(this);
                },
                ctx -> {
                    faceAndGreet();
                    if (onDuty() && !profile.pois.isEmpty() && tickCounter >= nextCommuteRetryTick) {
                        return QuestNpcState.COMMUTE;
                    }
                    return null;
                },
                ctx -> {
                }
        ));

        engine.register(QuestNpcState.COMMUTE, StateTreeNodes.of(
                ctx -> {
                    slot = service.locations().reserveSlot(pickPoiRef(), profile.key());
                    target = slot == null ? null : toLocation(slot.point());
                    if (target != null) {
                        warnedCommuteBlocked = false;
                        nextCommuteRetryTick = 0L;
                    }
                    stallCount = 0;
                    nextNavTick = 0L;
                    MobNavigationSupport.resetProgress(this);
                },
                ctx -> {
                    if (!onDuty()) {
                        return QuestNpcState.RETURN_HOME;
                    }
                    if (target == null) {
                        // No reservable POI (missing/wrong-scope refs, unloaded world): back off
                        // instead of silently ping-ponging OFF_DUTY <-> COMMUTE every tick, and
                        // say WHY once so "NPC never leaves home" is diagnosable from the log.
                        nextCommuteRetryTick = tickCounter + COMMUTE_RETRY_TICKS;
                        if (!warnedCommuteBlocked) {
                            warnedCommuteBlocked = true;
                            service.warnCommuteBlocked(profile);
                        }
                        return QuestNpcState.OFF_DUTY;
                    }
                    if (arrived(target)) {
                        return QuestNpcState.AT_POST;
                    }
                    drive(target);
                    return null;
                },
                ctx -> mob.getPathfinder().stopPathfinding()
        ));

        engine.register(QuestNpcState.AT_POST, StateTreeNodes.of(
                ctx -> {
                    mob.getPathfinder().stopPathfinding();
                    MobNavigationSupport.resetProgress(this);
                    dwellUntilTick = tickCounter + ThreadLocalRandom.current().nextLong(DWELL_MIN_TICKS, DWELL_MAX_TICKS);
                    if (slot != null) {
                        mob.setRotation(slot.point().yaw, slot.point().pitch);
                    }
                    service.onAgentArrivedAtPost(this);
                },
                ctx -> {
                    if (!onDuty()) {
                        return QuestNpcState.RETURN_HOME;
                    }
                    faceAndGreet();
                    if (tickCounter < dwellUntilTick) {
                        return null;
                    }
                    if (profile.pois.size() > 1 && ThreadLocalRandom.current().nextDouble() < POI_SWITCH_CHANCE) {
                        return QuestNpcState.COMMUTE;
                    }
                    if (profile.wanderRadius > 0.0D) {
                        return QuestNpcState.STROLL;
                    }
                    dwellUntilTick = tickCounter + ThreadLocalRandom.current().nextLong(DWELL_MIN_TICKS, DWELL_MAX_TICKS);
                    return null;
                },
                ctx -> {
                }
        ));

        engine.register(QuestNpcState.STROLL, StateTreeNodes.of(
                ctx -> {
                    strollGoal = pickStrollGoal();
                    stallCount = 0;
                    nextNavTick = 0L;
                    MobNavigationSupport.resetProgress(this);
                },
                ctx -> {
                    if (!onDuty()) {
                        return QuestNpcState.RETURN_HOME;
                    }
                    if (strollGoal == null
                            || arrived(strollGoal)
                            || tickCounter - stateEnteredTick > STROLL_TIMEOUT_TICKS) {
                        return QuestNpcState.AT_POST;
                    }
                    drive(strollGoal);
                    return null;
                },
                ctx -> {
                    strollGoal = null;
                    mob.getPathfinder().stopPathfinding();
                }
        ));

        engine.register(QuestNpcState.RETURN_HOME, StateTreeNodes.of(
                ctx -> {
                    releaseSlot();
                    target = toLocation(profile.home);
                    stallCount = 0;
                    nextNavTick = 0L;
                    MobNavigationSupport.resetProgress(this);
                },
                ctx -> {
                    if (target == null || arrived(target)) {
                        if (target != null && profile.home != null) {
                            mob.setRotation(profile.home.yaw, profile.home.pitch);
                        }
                        return QuestNpcState.OFF_DUTY;
                    }
                    // Re-opened schedule while walking home? Head straight back to work.
                    if (onDuty() && !profile.pois.isEmpty()) {
                        return QuestNpcState.COMMUTE;
                    }
                    drive(target);
                    return null;
                },
                ctx -> mob.getPathfinder().stopPathfinding()
        ));
    }

    // --- Behaviour helpers -----------------------------------------------------------------

    private boolean onDuty() {
        return profile.isOnDuty(service.clock().tickOfDay(mob.getWorld()));
    }

    private String pickPoiRef() {
        if (profile.pois.isEmpty()) {
            return null;
        }
        String current = slot == null ? null : slot.key();
        if (profile.pois.size() == 1) {
            return profile.pois.get(0);
        }
        // Prefer a POI other than the one we just worked so switches feel like a routine.
        for (int attempt = 0; attempt < 4; attempt++) {
            String candidate = profile.pois.get(ThreadLocalRandom.current().nextInt(profile.pois.size()));
            if (current == null || !current.startsWith(candidate.split(":", 2)[0])) {
                return candidate;
            }
        }
        return profile.pois.get(ThreadLocalRandom.current().nextInt(profile.pois.size()));
    }

    private boolean arrived(Location goal) {
        Location feet = mob.getLocation();
        if (!feet.getWorld().equals(goal.getWorld())) {
            return false;
        }
        return feet.distanceSquared(goal) <= ARRIVE_SQ;
    }

    /** Issues/refreshes pathfinding toward {@code goal}; teleports after repeated stalls. */
    private void drive(Location goal) {
        if (MobNavigationSupport.consumeStall(this)) {
            stallCount++;
            if (stallCount >= MAX_STALLS_BEFORE_TELEPORT) {
                stallCount = 0;
                mob.getPathfinder().stopPathfinding();
                mob.teleportAsync(goal.clone());
                return;
            }
            nextNavTick = 0L;
        }
        if (tickCounter < nextNavTick) {
            return;
        }
        nextNavTick = tickCounter + NAV_REFRESH_TICKS;
        Location prepared = MobNavigationSupport.prepareDestination(goal, mob.getLocation());
        MobNavigationSupport.navigateTo(this, prepared != null ? prepared : goal, profile.walkSpeed);
    }

    private Location pickStrollGoal() {
        Location center = slot != null ? toLocation(slot.point()) : mob.getLocation();
        if (center == null) {
            return null;
        }
        double radius = Math.max(2.0D, profile.wanderRadius);
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double dist = ThreadLocalRandom.current().nextDouble(1.5D, radius);
            Location candidate = center.clone().add(Math.cos(angle) * dist, 0.0D, Math.sin(angle) * dist);
            Location snapped = MobTerrainSupport.snapToStandableNear(candidate, 3);
            if (snapped != null) {
                return snapped;
            }
        }
        return null;
    }

    /**
     * Faces the nearest player in range and whispers the greeting on a per-player cooldown.
     *
     * <p>Facing rotates the WHOLE body ({@code setRotation}), not just {@code lookAt}: the agent
     * only ticks every {@value #TICK_PERIOD} game ticks, and a one-shot lookAt lets vanilla head
     * decay pull the head back toward the (post/home) body yaw between ticks — the NPC visibly
     * flicked player → idle → player. The idle facing is restored once, after the area has been
     * clear for a linger window, instead of tugging against the player-facing every tick.
     */
    private void faceAndGreet() {
        Player nearest = null;
        double nearestSq = FACE_RADIUS * FACE_RADIUS;
        for (Player player : mob.getLocation().getNearbyPlayers(FACE_RADIUS)) {
            double distSq = player.getLocation().distanceSquared(mob.getLocation());
            if (distSq < nearestSq) {
                nearest = player;
                nearestSq = distSq;
            }
        }
        if (nearest == null) {
            if (facingPlayer && tickCounter >= faceHoldUntilTick) {
                facingPlayer = false;
                restoreIdleFacing();
            }
            return;
        }
        facingPlayer = true;
        faceHoldUntilTick = tickCounter + FACE_LINGER_TICKS;
        facePlayer(nearest);
        if (profile.greeting == null || profile.greeting.isBlank() || nearestSq > GREET_RADIUS * GREET_RADIUS) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = greetedAtMs.get(nearest.getUniqueId());
        if (last != null && now - last < GREET_COOLDOWN_MS) {
            return;
        }
        greetedAtMs.put(nearest.getUniqueId(), now);
        service.sendGreeting(nearest, profile);
    }

    /** Body + head toward the player's eyes; lookAt on top smooths the head between agent ticks. */
    private void facePlayer(Player target) {
        Location feet = mob.getLocation();
        Location eye = target.getEyeLocation();
        double dx = eye.getX() - feet.getX();
        double dz = eye.getZ() - feet.getZ();
        double horizontalSq = dx * dx + dz * dz;
        if (horizontalSq < 1.0E-4D) {
            return;
        }
        double dy = eye.getY() - (feet.getY() + mob.getEyeHeight());
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(horizontalSq)));
        mob.setRotation(yaw, pitch);
        mob.lookAt(target);
    }

    /** Back to the configured post/home facing after players leave — once, not per tick. */
    private void restoreIdleFacing() {
        if (state() == QuestNpcState.AT_POST && slot != null) {
            mob.setRotation(slot.point().yaw, slot.point().pitch);
            return;
        }
        if (profile.home != null) {
            mob.setRotation(profile.home.yaw, profile.home.pitch);
        }
    }

    private void releaseSlot() {
        service.locations().releaseSlots(profile.key());
        slot = null;
    }

    private Location toLocation(network.skypvp.paper.model.WorldPoint point) {
        if (point == null) {
            return null;
        }
        World world = mob.getServer().getWorld(point.world);
        if (world == null) {
            return null;
        }
        return new Location(world, point.x, point.y, point.z, point.yaw, point.pitch);
    }
}
