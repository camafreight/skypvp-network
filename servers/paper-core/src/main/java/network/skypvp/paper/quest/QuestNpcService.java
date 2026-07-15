package network.skypvp.paper.quest;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.questsignal.QuestSignalDelivery;
import network.skypvp.paper.questsignal.QuestSignalProvider;
import network.skypvp.paper.questsignal.QuestSignalService;
import network.skypvp.paper.repository.QuestNpcRepository;
import network.skypvp.paper.waypoint.Waypoint;
import network.skypvp.paper.waypoint.WaypointMarker;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Lifecycle manager for dynamic quest NPCs ({@code /quest npc …}).
 *
 * <p>Each profile spawns a real pathfinding mob driven by a {@link QuestNpcAgent} state tree:
 * daily schedules from {@link QuestClock}, POI slots from {@link QuestLocationRegistry}, and an
 * optional navigator beacon delivered through {@link QuestSignalService} — so players standing
 * at spawn get a beam to every on-duty quest NPC the moment they enter the hub, without having
 * to find and click the NPC first.
 *
 * <p>All configuration persists to Postgres via {@link QuestNpcRepository}: servers boot from
 * ephemeral Docker images, so local files do not survive a restart. Without a database the
 * service still runs, but edits live only in memory.
 *
 * <p>Folia-safe: spawns run on the home region, agents tick on entity-pinned timers, and the
 * respawn watchdog only reads validity flags before rescheduling region-owned work.
 */
public final class QuestNpcService implements Listener {

    private static final Gson GSON = new Gson();
    private static final long WATCHDOG_PERIOD_TICKS = 100L;
    private static final double DUPLICATE_SCAN_RADIUS = 48.0D;
    private static final Color BEACON_COLOR = Color.fromRGB(255, 190, 40);

    private final PaperCorePlugin plugin;
    private final NamespacedKey npcIdKey;
    private final QuestNpcRepository repository;
    private final QuestLocationRegistry locations;
    private final QuestClock clock = new QuestClock();
    private final Map<String, QuestNpcProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, QuestNpcAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, PlatformTask> agentTasks = new ConcurrentHashMap<>();
    /** Last known post/home location per NPC — safe to read from any thread for beacons. */
    private final Map<String, Location> lastKnownPosts = new ConcurrentHashMap<>();
    /** NPCs currently mid-spawn, so the watchdog doesn't double-submit. */
    private final Map<String, Boolean> spawning = new ConcurrentHashMap<>();
    private PlatformTask watchdogTask;

    /** @param repository nullable — without a database the service runs without persistence. */
    public QuestNpcService(PaperCorePlugin plugin, QuestNpcRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.npcIdKey = new NamespacedKey(plugin, "quest_npc_id");
        this.repository = repository;
        this.locations = new QuestLocationRegistry(repository, plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public QuestLocationRegistry locations() {
        return locations;
    }

    public QuestClock clock() {
        return clock;
    }

    public Map<String, QuestNpcProfile> profiles() {
        return Map.copyOf(profiles);
    }

    public QuestNpcProfile profile(String id) {
        return id == null ? null : profiles.get(id.toLowerCase(Locale.ROOT));
    }

    public QuestNpcAgent agent(String id) {
        return id == null ? null : agents.get(id.toLowerCase(Locale.ROOT));
    }

    public PaperCorePlugin plugin() {
        return plugin;
    }

    public String decorationScope() {
        return plugin.decorationScope();
    }

    // --- Lifecycle -------------------------------------------------------------------------

    public void enable() {
        if (repository == null) {
            plugin.getLogger().warning("[QuestNpc] No database configured — quest NPC edits will not survive a restart.");
        } else {
            try {
                repository.ensureSchema().join();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("[QuestNpc] Failed to prepare quest tables: " + ex.getMessage());
            }
        }
        loadLocations();
        loadProfiles();
        loadClock();
        for (QuestNpcProfile profile : profiles.values()) {
            if (profile.beacon) {
                registerBeaconProvider(profile);
            }
        }
        warnUnresolvedPoiRefs();
        watchdogTask = plugin.platformScheduler().runGlobalTimer(this::ensureAgents, 60L, WATCHDOG_PERIOD_TICKS);
    }

    public void shutdown() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        for (String key : new ArrayList<>(agents.keySet())) {
            despawnAgent(key);
        }
    }

    /** Re-reads everything from Postgres and rebuilds the agents ({@code /quest reload}). */
    public void reload() {
        for (String key : new ArrayList<>(agents.keySet())) {
            despawnAgent(key);
        }
        for (QuestNpcProfile profile : profiles.values()) {
            unregisterBeaconProvider(profile.key());
        }
        profiles.clear();
        loadLocations();
        loadProfiles();
        loadClock();
        for (QuestNpcProfile profile : profiles.values()) {
            if (profile.beacon) {
                registerBeaconProvider(profile);
            }
        }
        warnUnresolvedPoiRefs();
        ensureAgents();
    }

    // --- Profile mutations (called from /quest) ----------------------------------------------

    public void putProfile(QuestNpcProfile profile) {
        if (profile.normalizedScope().isEmpty()) {
            profile.scope = decorationScope();
        }
        profiles.put(profile.key(), profile);
        saveProfile(profile);
        if (profile.beacon) {
            registerBeaconProvider(profile);
        } else {
            unregisterBeaconProvider(profile.key());
        }
    }

    public boolean removeProfile(String id) {
        String key = id == null ? null : id.toLowerCase(Locale.ROOT);
        QuestNpcProfile removed = key == null ? null : profiles.remove(key);
        if (removed == null) {
            return false;
        }
        despawnAgent(key);
        unregisterBeaconProvider(key);
        lastKnownPosts.remove(key);
        if (repository != null) {
            repository.deleteNpc(key, removed.normalizedScope()).exceptionally(error -> null);
        }
        return true;
    }

    /**
     * Moves a loaded NPC to another decoration scope. Removes it from this server's live set;
     * the destination gamemode picks it up on next reload/boot.
     */
    public boolean moveProfileScope(QuestNpcProfile profile, String newScope) {
        String to = QuestScopes.normalize(newScope);
        if (!QuestScopes.isQuestScope(to) || profile == null) {
            return false;
        }
        String from = profile.normalizedScope().isEmpty() ? decorationScope() : profile.normalizedScope();
        if (from.equals(to)) {
            return true;
        }
        String key = profile.key();
        if (repository != null) {
            profile.scope = to;
            Boolean moved = repository.moveNpcScope(key, from, to).exceptionally(error -> false).join();
            if (!Boolean.TRUE.equals(moved)) {
                // Target id already exists there, or row missing — fall back to upsert+delete.
                repository.upsertNpc(key, to, GSON.toJson(profile)).exceptionally(error -> null).join();
                repository.deleteNpc(key, from).exceptionally(error -> null);
            } else {
                repository.upsertNpc(key, to, GSON.toJson(profile)).exceptionally(error -> null);
            }
        } else {
            profile.scope = to;
        }
        profiles.remove(key);
        despawnAgent(key);
        unregisterBeaconProvider(key);
        lastKnownPosts.remove(key);
        return true;
    }

    /**
     * Moves a loaded POI to another decoration scope. Unloads it from this server's pool;
     * the destination gamemode picks it up on next reload/boot.
     */
    public boolean moveLocationScope(QuestPoi poi, String newScope) {
        String to = QuestScopes.normalize(newScope);
        if (!QuestScopes.isQuestScope(to) || poi == null) {
            return false;
        }
        String from = poi.normalizedScope().isEmpty() ? decorationScope() : poi.normalizedScope();
        if (from.equals(to)) {
            return true;
        }
        String key = poi.name == null ? "" : poi.name.toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return false;
        }
        if (repository != null) {
            poi.scope = to;
            Boolean moved = repository.movePoiScope(key, from, to).exceptionally(error -> false).join();
            if (!Boolean.TRUE.equals(moved)) {
                repository.upsertPoi(key, to, GSON.toJson(poi)).exceptionally(error -> null).join();
                repository.deletePoi(key, from).exceptionally(error -> null);
            } else {
                repository.upsertPoi(key, to, GSON.toJson(poi)).exceptionally(error -> null);
            }
        } else {
            poi.scope = to;
        }
        locations.unload(key);
        return true;
    }

    /** Re-plans the routine after schedule/POI/home edits; respawns when the mob type changed. */
    public void applyProfileEdit(QuestNpcProfile profile, boolean entityChanged) {
        saveProfile(profile);
        String key = profile.key();
        if (profile.beacon) {
            registerBeaconProvider(profile);
        } else {
            unregisterBeaconProvider(key);
        }
        QuestNpcAgent agent = agents.get(key);
        if (agent == null) {
            return;
        }
        if (entityChanged) {
            despawnAgent(key);
            return; // watchdog respawns with the new type
        }
        plugin.platformScheduler().runAtEntity(agent.entity(), agent::resetRoutine);
    }

    public void saveClock() {
        if (repository != null) {
            repository.saveClock(GSON.toJson(clock.settings())).exceptionally(error -> null);
        }
    }

    // --- Spawning --------------------------------------------------------------------------

    private void ensureAgents() {
        for (QuestNpcProfile profile : profiles.values()) {
            String key = profile.key();
            QuestNpcAgent agent = agents.get(key);
            if (agent != null && agent.entity().isValid() && !agent.entity().isDead()) {
                continue;
            }
            if (agent != null) {
                despawnAgent(key);
            }
            if (spawning.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }
            World world = Bukkit.getWorld(profile.home.world);
            if (world == null) {
                spawning.remove(key);
                continue;
            }
            Location home = new Location(world, profile.home.x, profile.home.y, profile.home.z, profile.home.yaw, profile.home.pitch);
            Location lastPost = lastKnownPosts.get(key);
            // Purge tagged orphans near home AND last post before spawning — a 48b home-only scan
            // misses NPCs that wandered away and then lost their agent binding (stale vanilla mobs).
            plugin.platformScheduler().runAtLocation(home, () -> removeStrayEntities(home, key));
            if (lastPost != null && lastPost.getWorld() != null
                    && (home.getWorld() == null || !lastPost.getWorld().equals(home.getWorld())
                    || lastPost.distanceSquared(home) > 4.0D)) {
                plugin.platformScheduler().runAtLocation(lastPost, () -> removeStrayEntities(lastPost, key));
            }
            plugin.platformScheduler().runAtLocation(home, () -> {
                try {
                    spawnAgent(profile, home);
                } finally {
                    spawning.remove(key);
                }
            });
        }
    }

    /** Must run on the region owning {@code home}. */
    private void spawnAgent(QuestNpcProfile profile, Location home) {
        String key = profile.key();
        if (!profiles.containsKey(key) || agents.containsKey(key)) {
            return;
        }
        removeStrayEntities(home, key);
        EntityType type = resolveEntityType(profile.entityType);
        if (type == null || type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) {
            plugin.getLogger().warning("[QuestNpc] '" + key + "' has non-mob entity type " + profile.entityType + "; skipping spawn.");
            return;
        }
        Mob mob;
        try {
            mob = (Mob) home.getWorld().spawn(home, type.getEntityClass().asSubclass(Entity.class), entity -> {
                Mob spawned = (Mob) entity;
                spawned.customName(ServerTextUtil.miniMessageComponent(profile.displayName));
                spawned.setCustomNameVisible(true);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setPersistent(false);
                spawned.setRemoveWhenFarAway(false);
                spawned.setCanPickupItems(false);
                spawned.setCollidable(false);
                spawned.setVisualFire(false);
                spawned.addPotionEffect(new PotionEffect(
                        PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                if (spawned instanceof Ageable ageable) {
                    ageable.setAdult();
                    ageable.setAgeLock(true);
                }
                spawned.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, key);
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[QuestNpc] Failed to spawn '" + key + "': " + ex.getMessage());
            return;
        }
        // Villager brain + vanilla goals fight the state tree — strip on spawn and every agent tick.
        QuestNpcAiSupport.stripVanillaAi(mob);
        QuestNpcAgent agent = new QuestNpcAgent(this, profile, mob);
        agents.put(key, agent);
        applyNameDisplay(mob, profile);
        lastKnownPosts.put(key, home.clone());
        PlatformTask task = plugin.platformScheduler().runAtEntityTimer(
                mob,
                agent::tick,
                () -> agentTasks.remove(key),
                QuestNpcAgent.TICK_PERIOD,
                QuestNpcAgent.TICK_PERIOD
        );
        agentTasks.put(key, task);
    }

    private void despawnAgent(String key) {
        QuestNpcAgent agent = agents.remove(key);
        PlatformTask task = agentTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
        locations.releaseSlots(key);
        if (agent != null) {
            Mob mob = agent.entity();
            plugin.platformScheduler().runAtEntity(mob, () -> {
                agent.shutdown();
                // Removing the vehicle only DISMOUNTS passengers; the nametag display would linger.
                removeNameDisplays(mob);
                if (mob.isValid()) {
                    mob.remove();
                }
            });
        }
    }

    /**
     * Applies the profile's name lines to the live mob. A single line uses the vanilla nametag;
     * multiple lines mount one multi-line {@link org.bukkit.entity.TextDisplay} passenger (same
     * pattern as the breach mob nametags) and hide the vanilla name. Must run on the mob's
     * region thread.
     */
    private void applyNameDisplay(Mob mob, QuestNpcProfile profile) {
        removeNameDisplays(mob);
        List<String> lines = profile.nameLines();
        if (lines.size() <= 1) {
            mob.customName(ServerTextUtil.miniMessageComponent(profile.displayName));
            mob.setCustomNameVisible(true);
            return;
        }
        net.kyori.adventure.text.Component joined = net.kyori.adventure.text.Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                joined = joined.append(net.kyori.adventure.text.Component.newline());
            }
            joined = joined.append(ServerTextUtil.miniMessageComponent(lines.get(index)));
        }
        net.kyori.adventure.text.Component text = joined;
        String key = profile.key();
        org.bukkit.entity.TextDisplay display = mob.getWorld().spawn(
                mob.getLocation(),
                org.bukkit.entity.TextDisplay.class,
                entity -> {
                    entity.text(text);
                    entity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                    entity.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
                    entity.setPersistent(false);
                    entity.setSeeThrough(true);
                    entity.setShadowed(true);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setLineWidth(220);
                    entity.setViewRange(48.0F);
                    entity.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(0.0F, 0.45F, 0.0F),
                            new org.joml.AxisAngle4f(),
                            new org.joml.Vector3f(1.0F, 1.0F, 1.0F),
                            new org.joml.AxisAngle4f()
                    ));
                    entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, key);
                }
        );
        // Keep the vanilla name set (for targeting/logs) but hidden — the display replaces it.
        mob.customName(ServerTextUtil.miniMessageComponent(profile.displayName));
        if (mob.addPassenger(display)) {
            mob.setCustomNameVisible(false);
        } else {
            display.remove();
            mob.setCustomNameVisible(true);
        }
    }

    private void removeNameDisplays(Mob mob) {
        for (Entity passenger : mob.getPassengers()) {
            if (passenger instanceof org.bukkit.entity.TextDisplay
                    && passenger.getPersistentDataContainer().has(npcIdKey, PersistentDataType.STRING)) {
                passenger.remove();
            }
        }
    }

    /** Persists a name-line edit and refreshes the live nametag in place (no respawn, no routine reset). */
    public void applyNameEdit(QuestNpcProfile profile) {
        saveProfile(profile);
        QuestNpcAgent agent = agents.get(profile.key());
        if (agent == null) {
            return;
        }
        Mob mob = agent.entity();
        plugin.platformScheduler().runAtEntity(mob, () -> {
            if (mob.isValid() && !mob.isDead()) {
                applyNameDisplay(mob, profile);
            }
        });
    }

    private void removeStrayEntities(Location around, String key) {
        World world = around.getWorld();
        if (world == null || key == null) {
            return;
        }
        QuestNpcAgent live = agents.get(key);
        UUID keepUuid = live != null && live.entity() != null ? live.entity().getUniqueId() : null;
        for (Entity entity : world.getNearbyEntities(around, DUPLICATE_SCAN_RADIUS, DUPLICATE_SCAN_RADIUS, DUPLICATE_SCAN_RADIUS)) {
            String tagged = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
            if (!key.equals(tagged)) {
                continue;
            }
            // Never delete the currently bound agent entity mid-tick.
            if (keepUuid != null && keepUuid.equals(entity.getUniqueId())) {
                continue;
            }
            if (entity instanceof Mob mob) {
                removeNameDisplays(mob);
            }
            entity.remove();
        }
    }

    /** Public so chunk-load reclaim can strip / remove orphans on the entity region thread. */
    NamespacedKey npcIdKey() {
        return npcIdKey;
    }

    void reclaimLoadedEntity(Entity entity) {
        if (entity == null || !(entity instanceof Mob mob)) {
            return;
        }
        String key = entity.getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        if (key == null || key.isBlank()) {
            return;
        }
        QuestNpcProfile profile = profiles.get(key);
        if (profile == null) {
            // Definition gone — remove leftover tagged mob + nametag passengers.
            removeNameDisplays(mob);
            mob.remove();
            return;
        }
        QuestNpcAgent agent = agents.get(key);
        if (agent != null && agent.entity().isValid() && !agent.entity().isDead()) {
            if (agent.entity().getUniqueId().equals(mob.getUniqueId())) {
                QuestNpcAiSupport.stripVanillaAi(mob);
                return;
            }
            // Duplicate tagged body while a live agent exists — cull the stray.
            removeNameDisplays(mob);
            mob.remove();
            return;
        }
        // No live agent: strip AI so it can't wander as a vanilla villager until watchdog respawns.
        QuestNpcAiSupport.stripVanillaAi(mob);
        mob.getPathfinder().stopPathfinding();
    }

    private static EntityType resolveEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return EntityType.VILLAGER;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // --- Agent callbacks ---------------------------------------------------------------------

    /** Called on the agent's region thread when it settles at a POI slot. */
    void onAgentArrivedAtPost(QuestNpcAgent agent) {
        String key = agent.profile().key();
        Location post = agent.currentLocation().clone();
        lastKnownPosts.put(key, post);
        if (!agent.profile().beacon) {
            return;
        }
        // Players already following this NPC's beacon get retargeted to the new post.
        String waypointId = QuestSignalService.waypointIdFor(beaconQuestId(key));
        plugin.platform().runForEachPlayer(player -> {
            if (plugin.waypointNavigator() != null
                    && plugin.waypointNavigator().isNavigating(player.getUniqueId(), waypointId)
                    && player.getWorld().equals(post.getWorld())) {
                plugin.waypointNavigator().navigate(player, beaconWaypoint(agent.profile(), post));
            }
        });
    }

    /** Called (once per blockage) by an agent that cannot reserve any POI slot and is stuck home. */
    void warnCommuteBlocked(QuestNpcProfile profile) {
        List<String> missing = missingPoiRefs(profile);
        plugin.getLogger().warning("[QuestNpc] '" + profile.key() + "' cannot reserve a POI slot from "
                + profile.pois + (missing.isEmpty() ? "" : " (unresolved in scope '" + decorationScope() + "': " + missing + ")")
                + " — staying at home. Create the POI on this gamemode or move it here with"
                + " /quest location scope <name> " + decorationScope() + " (run where the POI currently lives).");
    }

    /** POI refs on {@code profile} that don't resolve in this scope's pool. */
    private List<String> missingPoiRefs(QuestNpcProfile profile) {
        List<String> missing = new ArrayList<>();
        for (String ref : profile.pois) {
            if (!locations.refExists(ref)) {
                missing.add(ref);
            }
        }
        return missing;
    }

    /**
     * Boot/reload sanity pass: an NPC whose POI refs don't resolve (typo, or the POI lives in
     * another gamemode's scope) silently stands at home forever — say so in the log instead.
     */
    private void warnUnresolvedPoiRefs() {
        for (QuestNpcProfile profile : profiles.values()) {
            if (profile.pois.isEmpty()) {
                plugin.getLogger().warning("[QuestNpc] '" + profile.key()
                        + "' has no POIs assigned — it will never leave home. Assign one with /quest npc poi.");
                continue;
            }
            List<String> missing = missingPoiRefs(profile);
            if (!missing.isEmpty()) {
                plugin.getLogger().warning("[QuestNpc] '" + profile.key() + "' references POI(s) " + missing
                        + " missing from scope '" + decorationScope() + "' — it will stay at home until they exist here."
                        + " Move them with /quest location scope <name> " + decorationScope()
                        + " (run where the POI currently lives).");
            }
        }
    }

    void sendGreeting(Player player, QuestNpcProfile profile) {
        player.sendMessage(ServerTextUtil.miniMessageComponent(
                profile.displayName + " <dark_gray>»</dark_gray> <gray>" + profile.greeting + "</gray>"
        ));
    }

    // --- Beacons (waypoints available from spawn) ---------------------------------------------

    private static String beaconQuestId(String key) {
        return "questnpc-" + key;
    }

    /** Horizontal distance at which an NPC beacon counts as "reached" and clears itself. */
    private static final double BEACON_ARRIVE_BLOCKS = 3.0D;

    private Waypoint beaconWaypoint(QuestNpcProfile profile, Location at) {
        // autoClearBlocks > 0: the navigator tick clears the beam on arrival — measured against the
        // LIVE NPC position (live-target refresh runs before the distance check), so walking up to
        // the wandering NPC removes the beacon instead of leaving it pinned forever.
        return Waypoint.of(
                QuestSignalService.waypointIdFor(beaconQuestId(profile.key())),
                at,
                profile.displayName,
                BEACON_COLOR,
                BEACON_ARRIVE_BLOCKS
        ).withMarker(WaypointMarker.octagon(BEACON_COLOR, "<white>?</white>"));
    }

    /**
     * Called from the agent's entity-thread tick so beacons track the NPC as it wanders. Beam delivery reads
     * this map at evaluate time AND the navigator samples it per render tick via the live-target hook —
     * previously the map was written once at spawn (home), so beams pointed at a stale post all day.
     */
    void recordPost(String key, Location live) {
        if (key == null || live == null || live.getWorld() == null) {
            return;
        }
        Location previous = lastKnownPosts.get(key);
        if (previous != null
                && previous.getWorld() != null
                && previous.getWorld().equals(live.getWorld())
                && previous.distanceSquared(live) < 0.5625D) {
            return;
        }
        lastKnownPosts.put(key, live.clone());
    }

    private void registerBeaconProvider(QuestNpcProfile profile) {
        QuestSignalService signals = plugin.questSignals();
        if (signals == null) {
            return;
        }
        String key = profile.key();
        if (plugin.waypointNavigator() != null) {
            // Beam follows the NPC after delivery: the navigator samples the live post every render tick.
            plugin.waypointNavigator().registerLiveTarget(
                    QuestSignalService.waypointIdFor(beaconQuestId(key)),
                    () -> lastKnownPosts.get(key)
            );
        }
        signals.register(new QuestSignalProvider() {
            @Override
            public String questId() {
                return beaconQuestId(key);
            }

            @Override
            public String worldName() {
                QuestNpcProfile current = profiles.get(key);
                return current == null ? "" : current.home.world;
            }

            @Override
            public Optional<QuestSignalDelivery> evaluate(Player player) {
                QuestNpcProfile current = profiles.get(key);
                if (current == null || !current.beacon || current.paused) {
                    return Optional.empty();
                }
                if (!current.isOnDuty(clock.tickOfDay(player.getWorld()))) {
                    return Optional.empty();
                }
                Location post = lastKnownPosts.get(key);
                if (post == null || post.getWorld() == null || !post.getWorld().equals(player.getWorld())) {
                    return Optional.empty();
                }
                return Optional.of(new QuestSignalDelivery(
                        "<gold>◆</gold> <gray>" + current.displayName
                                + " <gray>is out and about — follow the beam to find them.</gray>",
                        beaconWaypoint(current, post)
                ));
            }
        });
    }

    private void unregisterBeaconProvider(String key) {
        QuestSignalService signals = plugin.questSignals();
        if (signals != null) {
            signals.unregister(beaconQuestId(key));
        }
        if (plugin.waypointNavigator() != null) {
            plugin.waypointNavigator().unregisterLiveTarget(QuestSignalService.waypointIdFor(beaconQuestId(key)));
        }
    }

    // --- Player interaction --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String key = event.getRightClicked().getPersistentDataContainer().get(npcIdKey, PersistentDataType.STRING);
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        QuestNpcProfile profile = profiles.get(key);
        if (profile == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getRightClicked() instanceof Mob mob) {
            mob.lookAt(player);
        }
        NetworkSoundCue.NPC_INTERACT.play(player);
        plugin.npcLibrary().executeInteractionAction(
                player,
                profile.actionType,
                profile.actionData,
                event.getRightClicked().getLocation(),
                event.getRightClicked()
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcIdKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    /**
     * Chunk reload can restore vanilla villager brain/goals and leave tagged orphans unbound from
     * an agent. Reclaim on the entity region thread: re-strip AI for the live agent, cull duplicates.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!entity.getPersistentDataContainer().has(npcIdKey, PersistentDataType.STRING)) {
                continue;
            }
            plugin.platformScheduler().runAtEntity(entity, () -> reclaimLoadedEntity(entity));
        }
    }

    // --- Persistence -----------------------------------------------------------------------

    private void saveProfile(QuestNpcProfile profile) {
        if (repository != null) {
            if (profile.normalizedScope().isEmpty()) {
                profile.scope = decorationScope();
            }
            repository.upsertNpc(profile.key(), profile.normalizedScope(), GSON.toJson(profile))
                    .exceptionally(error -> null);
        }
    }

    private void loadLocations() {
        if (repository == null) {
            return;
        }
        try {
            String scope = decorationScope();
            locations.applyLoaded(repository.loadPois(scope).join(), scope);
            plugin.getLogger().info("[QuestNpc] Loaded " + locations.all().size()
                    + " POI(s) for scope '" + scope + "'.");
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[QuestNpc] Failed to load POIs from database: " + ex.getMessage());
        }
    }

    private void loadProfiles() {
        if (repository == null) {
            return;
        }
        String scope = decorationScope();
        Map<String, String> rows;
        try {
            rows = repository.loadNpcs(scope).join();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[QuestNpc] Failed to load NPC profiles from database: " + ex.getMessage());
            return;
        }
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            String id = entry.getKey();
            String payload = entry.getValue();
            try {
                QuestNpcProfile profile = GSON.fromJson(payload, QuestNpcProfile.class);
                if (profile == null || profile.home == null) {
                    continue;
                }
                profile.id = id;
                if (profile.pois == null) {
                    profile.pois = new ArrayList<>();
                }
                if (profile.extraNameLines == null) {
                    profile.extraNameLines = new ArrayList<>();
                }
                profile.scope = scope;
                profiles.put(profile.key(), profile);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("[QuestNpc] Skipping malformed NPC row '" + id + "': " + ex.getMessage());
            }
        }
        plugin.getLogger().info("[QuestNpc] Loaded " + profiles.size()
                + " NPC profile(s) for scope '" + scope + "'.");
    }

    private void loadClock() {
        if (repository == null) {
            return;
        }
        try {
            repository.loadClock().join().ifPresent(payload ->
                    clock.apply(GSON.fromJson(payload, QuestClock.Settings.class)));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[QuestNpc] Failed to load quest clock from database: " + ex.getMessage());
        }
    }

    /** Debug summary lines for {@code /quest debug}. */
    public List<String> debugLines() {
        List<String> lines = new ArrayList<>();
        for (QuestNpcProfile profile : profiles.values()) {
            QuestNpcAgent agent = agents.get(profile.key());
            World world = Bukkit.getWorld(profile.home.world);
            long tickOfDay = clock.tickOfDay(world);
            List<String> missing = missingPoiRefs(profile);
            lines.add("<gray>• <white>" + profile.key() + "</white>"
                    + " state=<yellow>" + (agent == null ? "DESPAWNED" : agent.state()) + "</yellow>"
                    + " slot=<aqua>" + (agent == null ? "-" : agent.slotLabel()) + "</aqua>"
                    + " duty=" + (profile.isOnDuty(tickOfDay) ? "<green>on</green>" : "<red>off</red>")
                    + (profile.paused ? " <red>[paused]</red>" : "")
                    + (profile.beacon ? " <gold>[beacon]</gold>" : "")
                    + (profile.pois.isEmpty() ? " <red>[no POIs]</red>" : "")
                    + (missing.isEmpty() ? "" : " <red>[missing POIs: " + String.join(", ", missing) + "]</red>")
            );
        }
        return lines;
    }
}
