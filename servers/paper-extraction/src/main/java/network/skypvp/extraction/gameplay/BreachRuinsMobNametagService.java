package network.skypvp.extraction.gameplay;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.ai.raider.RaiderGroupRole;
import network.skypvp.paper.ai.statetree.CombatAgentStateId;
import network.skypvp.extraction.ai.raider.RaiderStateLabels;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiTextLibrary;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Passenger-mounted TextDisplay nametags for breach MythicMobs, using the same mount pattern as
 * {@link network.skypvp.paper.library.NametagLibrary} but bound to arbitrary {@link LivingEntity} hosts.
 */
public final class BreachRuinsMobNametagService implements Listener {

    private static final double BASE_HEIGHT = 0.38D;
    private static final double LINE_SPACING = 0.22D;
    private static final float SCALE = 0.48F;
    private static final int BAR_WIDTH = 10;
    private static final int TEXT_DISPLAY_LINE_WIDTH = 220;

    private static final int LAYOUT_VERSION = 3;

    private final JavaPlugin plugin;
    private final PaperCorePlugin core;
    private final NamespacedKey ownerKey;
    private final NamespacedKey layoutKey;
    private final Map<UUID, MobNametag> active = new ConcurrentHashMap<>();

    public BreachRuinsMobNametagService(JavaPlugin plugin, PaperCorePlugin core) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.ownerKey = new NamespacedKey(plugin, "breach_mob_nametag_owner");
        this.layoutKey = new NamespacedKey(plugin, "breach_mob_nametag_layout");
    }

    public void attach(LivingEntity entity, String mobType, String displayName) {
        if (entity == null || mobType == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        Runnable mount = () -> {
            if (!entity.isValid() || entity.isDead()) {
                return;
            }
            MobNametag existing = active.get(entity.getUniqueId());
            if (existing != null && isMountIntact(entity, existing)) {
                return;
            }
            MobNametag preserved = existing;
            detach(entity.getUniqueId());
            scrubOwnedDisplays(entity);
            entity.setCustomNameVisible(false);
            MobNametag tag = new MobNametag(
                    entity.getUniqueId(),
                    mobType.toLowerCase(Locale.ROOT),
                    blankToDefault(displayName, defaultName(mobType))
            );
            if (preserved != null) {
                tag.copyFrom(preserved);
            }
            TextDisplay nameLine = spawnLine(entity, tag.nameComponent(), 2);
            TextDisplay healthLine = spawnLine(entity, tag.healthComponent(entity), 1);
            TextDisplay stateLine = spawnLine(entity, tag.stateComponent(), 0);
            if (nameLine == null || healthLine == null || stateLine == null) {
                removeDisplay(nameLine == null ? null : nameLine.getUniqueId());
                removeDisplay(healthLine == null ? null : healthLine.getUniqueId());
                removeDisplay(stateLine == null ? null : stateLine.getUniqueId());
                return;
            }
            tag.nameDisplayId = nameLine.getUniqueId();
            tag.healthDisplayId = healthLine.getUniqueId();
            tag.stateDisplayId = stateLine.getUniqueId();
            active.put(entity.getUniqueId(), tag);
        };
        core.platform().runAtEntity(entity, mount);
    }

    public void refresh(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        MobNametag tag = active.get(entity.getUniqueId());
        if (tag == null) {
            return;
        }
        core.platform().runAtEntity(entity, () -> {
            if (!entity.isValid() || entity.isDead()) {
                detach(entity.getUniqueId());
                return;
            }
            if (!isMountIntact(entity, tag)) {
                attach(entity, tag.mobType, tag.displayName);
                return;
            }
            refreshLinesOnEntityThread(entity, tag);
        });
    }

    /** Must run on the host entity's region thread with an intact mount. */
    private void refreshLinesOnEntityThread(LivingEntity entity, MobNametag tag) {
        tag.lastName = updateDisplayIfChanged(tag.nameDisplayId, tag.nameComponent(), tag.lastName);
        tag.lastHealth = updateDisplayIfChanged(tag.healthDisplayId, tag.healthComponent(entity), tag.lastHealth);
        tag.lastState = updateDisplayIfChanged(tag.stateDisplayId, tag.stateComponent(), tag.lastState);
    }

    private Component updateDisplayIfChanged(UUID displayId, Component text, Component lastSent) {
        if (Objects.equals(text, lastSent)) {
            return lastSent;
        }
        updateDisplay(displayId, text);
        return text;
    }

    public void updateAiState(LivingEntity entity, CombatAgentStateId state) {
        updateAiState(entity, state, RaiderGroupRole.SOLO, 1, null);
    }

    public void updateAiState(LivingEntity entity, CombatAgentStateId state, RaiderGroupRole role, int groupSize) {
        updateAiState(entity, state, role, groupSize, null);
    }

    public void updateAiState(
            LivingEntity entity,
            CombatAgentStateId state,
            RaiderGroupRole role,
            int groupSize,
            UUID groupId
    ) {
        if (entity == null || state == null) {
            return;
        }
        MobNametag tag = active.get(entity.getUniqueId());
        if (tag == null) {
            return;
        }
        RaiderGroupRole resolvedRole = role == null ? RaiderGroupRole.SOLO : role;
        int resolvedSize = Math.max(1, groupSize);
        if (tag.aiState == state
                && tag.groupRole == resolvedRole
                && tag.groupSize == resolvedSize
                && Objects.equals(tag.groupId, groupId)) {
            return;
        }
        tag.aiState = state;
        tag.groupRole = resolvedRole;
        tag.groupSize = resolvedSize;
        tag.groupId = groupId;
        core.platform().runAtEntity(entity, () -> {
            if (!entity.isValid() || entity.isDead()) {
                detach(entity.getUniqueId());
                return;
            }
            if (!isMountIntact(entity, tag)) {
                attach(entity, tag.mobType, tag.displayName);
                return;
            }
            tag.lastName = updateDisplayIfChanged(tag.nameDisplayId, tag.nameComponent(), tag.lastName);
            tag.lastState = updateDisplayIfChanged(tag.stateDisplayId, tag.stateComponent(), tag.lastState);
        });
    }

    /** Heartbeat cadence for the periodic health-line/mount sweep (the caller ticks every tick). */
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private int tickCounter;

    public void tick() {
        // This used to fan out 2 nested runAtEntity tasks per mob EVERY tick and rebuild
        // three components each time — pure scheduling and allocation churn. State/group
        // changes push their own updates via updateAiState; this sweep only needs to catch
        // health changes and broken mounts, so a 10-tick cadence with change-gated sends
        // (see updateDisplayIfChanged) is plenty.
        if (++tickCounter % REFRESH_INTERVAL_TICKS != 0) {
            return;
        }
        for (UUID entityId : List.copyOf(active.keySet())) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                detach(entityId);
                continue;
            }
            core.platform().runAtEntity(living, () -> {
                if (!living.isValid() || living.isDead()) {
                    detach(entityId);
                    return;
                }
                MobNametag tag = active.get(entityId);
                if (tag == null) {
                    return;
                }
                if (!isMountIntact(living, tag)) {
                    attach(living, tag.mobType, tag.displayName);
                } else {
                    refreshLinesOnEntityThread(living, tag);
                }
            });
        }
    }

    public void attachIfAbsent(LivingEntity entity, String mobType, String displayName) {
        if (entity == null || mobType == null) {
            return;
        }
        MobNametag existing = active.get(entity.getUniqueId());
        if (existing != null && isMountIntact(entity, existing)) {
            return;
        }
        attach(entity, mobType, displayName);
    }

    public void detach(LivingEntity entity) {
        if (entity != null) {
            detach(entity.getUniqueId());
        }
    }

    public void detach(UUID entityId) {
        MobNametag tag = active.remove(entityId);
        if (tag == null) {
            return;
        }
        Entity host = plugin.getServer().getEntity(entityId);
        Runnable cleanup = () -> {
            removeDisplay(tag.nameDisplayId);
            removeDisplay(tag.healthDisplayId);
            removeDisplay(tag.stateDisplayId);
        };
        if (host != null && host.isValid()) {
            core.platform().runAtEntity(host, cleanup);
        } else {
            cleanup.run();
        }
    }

    public void syncViewer(Player viewer, double radiusSq) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        World world = viewer.getWorld();
        for (Map.Entry<UUID, MobNametag> entry : active.entrySet()) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !world.equals(entity.getWorld())) {
                continue;
            }
            if (viewer.getLocation().distanceSquared(living.getLocation()) > radiusSq) {
                continue;
            }
            core.platform().runAtEntity(living, () -> {
                MobNametag tag = entry.getValue();
                if (!isMountIntact(living, tag)) {
                    attach(living, tag.mobType, tag.displayName);
                } else {
                    refresh(living);
                }
            });
        }
    }

    public void purgeWorld(World world) {
        if (world == null) {
            return;
        }
        for (UUID entityId : List.copyOf(active.keySet())) {
            Entity entity = plugin.getServer().getEntity(entityId);
            if (entity == null || world.equals(entity.getWorld())) {
                detach(entityId);
            }
        }
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            String owner = display.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (owner != null) {
                display.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemoved(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (entity != null && active.containsKey(entity.getUniqueId())) {
            detach(entity.getUniqueId());
        }
    }

    public static String dangerIndicator(String mobType) {
        if (mobType == null) {
            return "<gray>\u2620";
        }
        return switch (mobType.toLowerCase(Locale.ROOT)) {
            case "ruinssmgunner" -> "<yellow>\u2620";
            case "ruinsrifleman" -> "<gold>\u2620\u2620";
            case "ruinsbreacher" -> "<red>\u2620\u2620\u2620";
            case "ruinspistolgunner" -> "<yellow>\u2620\u2620";
            case "ruinskniferusher" -> "<dark_red>\u2620";
            default -> "<gray>\u2620";
        };
    }

    private boolean isMountIntact(LivingEntity host, MobNametag tag) {
        if (tag.nameDisplayId == null || tag.healthDisplayId == null || tag.stateDisplayId == null) {
            return false;
        }
        for (UUID displayId : new UUID[] {tag.nameDisplayId, tag.healthDisplayId, tag.stateDisplayId}) {
            Entity display = plugin.getServer().getEntity(displayId);
            if (!(display instanceof TextDisplay textDisplay)
                    || !textDisplay.isValid()
                    || textDisplay.isDead()
                    || !host.equals(textDisplay.getVehicle())
                    || !usesCurrentLayout(textDisplay)) {
                return false;
            }
        }
        return true;
    }

    private void scrubOwnedDisplays(LivingEntity host) {
        for (Entity passenger : host.getPassengers()) {
            if (!(passenger instanceof TextDisplay display) || !display.isValid()) {
                continue;
            }
            String owner = display.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (owner != null && owner.equals(host.getUniqueId().toString())) {
                display.remove();
            }
        }
        for (Entity nearby : host.getNearbyEntities(1.25D, 4.0D, 1.25D)) {
            if (!(nearby instanceof TextDisplay display) || !display.isValid()) {
                continue;
            }
            String owner = display.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (owner != null && owner.equals(host.getUniqueId().toString())) {
                display.remove();
            }
        }
    }

    private TextDisplay spawnLine(LivingEntity host, Component text, int lineIndex) {
        double offsetY = BASE_HEIGHT + (double) lineIndex * LINE_SPACING * SCALE;
        TextDisplay display = host.getWorld().spawn(host.getLocation(), TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setPersistent(false);
            entity.setSeeThrough(true);
            entity.setShadowed(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(TEXT_DISPLAY_LINE_WIDTH);
            entity.setViewRange(48.0F);
            entity.setTransformation(lineTransformation(offsetY));
            entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, host.getUniqueId().toString());
            entity.getPersistentDataContainer().set(layoutKey, PersistentDataType.INTEGER, LAYOUT_VERSION);
        });
        if (!host.addPassenger(display)) {
            display.remove();
            return null;
        }
        return display;
    }

    private void updateDisplay(UUID displayId, Component text) {
        if (displayId == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(displayId);
        if (entity instanceof TextDisplay display && display.isValid()) {
            display.text(text);
        }
    }

    private void removeDisplay(UUID displayId) {
        if (displayId == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(displayId);
        if (entity != null && entity.isValid()) {
            core.platform().runAtEntity(entity, entity::remove);
        }
    }

    private boolean usesCurrentLayout(TextDisplay display) {
        Integer version = display.getPersistentDataContainer().get(layoutKey, PersistentDataType.INTEGER);
        return version != null && version == LAYOUT_VERSION;
    }

    private static Transformation lineTransformation(double offsetY) {
        return new Transformation(
                new Vector3f(0.0F, (float) offsetY, 0.0F),
                new AxisAngle4f(),
                new Vector3f(SCALE, SCALE, SCALE),
                new AxisAngle4f()
        );
    }

    private static Component healthBar(LivingEntity entity) {
        MythicMobHealthUtil.HealthSnapshot health = MythicMobHealthUtil.snapshot(entity);
        int hp = (int) Math.ceil(health.current());
        int cap = (int) Math.ceil(health.max());
        String bar = GuiTextLibrary.progressBar(hp, cap, BAR_WIDTH, "<red>", "<dark_gray>");
        return ServerTextUtil.miniMessageComponent(bar);
    }

    private static String defaultName(String mobType) {
        return switch (mobType.toLowerCase(Locale.ROOT)) {
            case "ruinssmgunner" -> "Ruins SMGunner";
            case "ruinsbreacher" -> "Ruins Breacher";
            case "ruinspistolgunner" -> "Ruins Pistol Gunner";
            case "ruinskniferusher" -> "Ruins Knife Rusher";
            default -> "Ruins Rifleman";
        };
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class MobNametag {
        private final UUID entityId;
        private final String mobType;
        private final String displayName;
        private UUID nameDisplayId;
        private UUID healthDisplayId;
        private UUID stateDisplayId;
        private CombatAgentStateId aiState = CombatAgentStateId.IDLE;
        private RaiderGroupRole groupRole = RaiderGroupRole.SOLO;
        private int groupSize = 1;
        private UUID groupId;
        /** Last components pushed to each display line; skips redundant rebuild + resend. */
        private Component lastName;
        private Component lastHealth;
        private Component lastState;

        private MobNametag(UUID entityId, String mobType, String displayName) {
            this.entityId = entityId;
            this.mobType = mobType;
            this.displayName = displayName;
        }

        private void copyFrom(MobNametag other) {
            aiState = other.aiState;
            groupRole = other.groupRole;
            groupSize = other.groupSize;
            groupId = other.groupId;
        }

        private Component nameComponent() {
            String safeName = MiniMessage.miniMessage().escapeTags(displayName);
            return ServerTextUtil.miniMessageComponent(
                    dangerIndicator(mobType) + " <white>" + safeName + RaiderStateLabels.teamTag(groupId, groupSize)
            );
        }

        private Component healthComponent(LivingEntity entity) {
            return healthBar(entity);
        }

        private Component stateComponent() {
            return ServerTextUtil.miniMessageComponent(
                    RaiderStateLabels.display(aiState, groupRole, groupSize)
            );
        }
    }
}
