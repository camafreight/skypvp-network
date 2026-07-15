package network.skypvp.extraction.gameplay.corpse;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.packet.PacketCorpseDisplay;
import network.skypvp.paper.library.packet.PacketEntityInteractionService;
import network.skypvp.paper.platform.ServerPlatform;
import network.skypvp.paper.service.CoreHotbarService;
import network.skypvp.paper.service.PlayerInventoryManager;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class BreachPlayerCorpseService {

    public static final String PROP_TYPE = "BREACH_CORPSE";

    private final JavaPlugin plugin;
    private final ServerPlatform scheduler;
    private final PaperCorePlugin core;
    private final NamespacedKey corpseIdKey;
    private final NamespacedKey propTypeKey;
    private final Map<UUID, BreachPlayerCorpseState> corpses = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> interactionToCorpse = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> earlyLootSnapshots = new ConcurrentHashMap<>();
    /**
     * One shared, live loot inventory per corpse. All looters open the SAME container instance so item removals are
     * reflected for everyone (Bukkit conserves items within a real inventory). Recreating a fresh snapshot per open
     * was what let two players each pull a full copy of the loot — the duplication bug.
     */
    private final Map<UUID, Inventory> liveInventories = new ConcurrentHashMap<>();
    /** Soft-spectators must not be able to open/loot corpses; injected by the gameplay coordinator. */
    private java.util.function.Predicate<Player> spectatorCheck;

    public BreachPlayerCorpseService(JavaPlugin plugin, ServerPlatform scheduler, PaperCorePlugin core) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.core = Objects.requireNonNull(core, "core");
        this.corpseIdKey = new NamespacedKey(plugin, "breach_corpse_id");
        this.propTypeKey = new NamespacedKey(plugin, "breach_corpse_type");
    }

    public void setSpectatorCheck(java.util.function.Predicate<Player> spectatorCheck) {
        this.spectatorCheck = spectatorCheck;
    }

    private boolean isSpectator(Player player) {
        return player != null && this.spectatorCheck != null && this.spectatorCheck.test(player);
    }

    public NamespacedKey corpseIdKey() {
        return this.corpseIdKey;
    }

    public NamespacedKey propTypeKey() {
        return this.propTypeKey;
    }

    public void rememberDeathLoot(
            Player owner,
            PlayerInventoryManager inventoryManager,
            CoreHotbarService hotbarService
    ) {
        if (owner == null) {
            return;
        }
        ItemStack[] snapshot = BreachPlayerCorpseLayout.captureLootFromPlayer(owner, inventoryManager, hotbarService, null);
        if (!BreachPlayerCorpseLayout.isEmpty(snapshot)) {
            this.earlyLootSnapshots.put(owner.getUniqueId(), snapshot);
        }
    }

    public void spawnCorpse(Player owner, PlayerInventoryManager inventoryManager, CoreHotbarService hotbarService) {
        this.spawnCorpse(owner, inventoryManager, hotbarService, null);
    }

    public void spawnCorpse(
            Player owner,
            PlayerInventoryManager inventoryManager,
            CoreHotbarService hotbarService,
            PlayerDeathEvent deathEvent
    ) {
        if (owner == null || inventoryManager == null || owner.getWorld() == null) {
            return;
        }
        Location deathLocation = owner.getLocation().clone();
        Optional<BreachCorpseGround.Anchor> groundOptional = BreachCorpseGround.resolve(deathLocation);
        if (groundOptional.isEmpty()) {
            this.plugin.getLogger().info("[Corpse] Skipped corpse for " + owner.getName()
                    + " (no solid ground under death location).");
            return;
        }
        BreachCorpseGround.Anchor ground = groundOptional.get();
        // When players die stacked on the same spot their corpses (and interaction hitboxes) would overlap, making
        // it impossible to target a specific body. Nudge each new corpse outward to a clear spot near the death.
        Location location = this.spreadIfOverlapping(ground.bodyLocation().clone());
        Collection<ItemStack> deathDrops = deathEvent == null ? null : deathEvent.getDrops();
        ItemStack[] loot = BreachPlayerCorpseLayout.captureLootFromPlayer(owner, inventoryManager, hotbarService, deathDrops);
        loot = this.mergeEarlySnapshot(owner.getUniqueId(), loot);
        if (BreachPlayerCorpseLayout.isEmpty(loot)) {
            this.plugin.getLogger().info("[Corpse] Skipped corpse for " + owner.getName()
                    + " (no loot to drop; inventory and death drops were empty or server items only).");
            return;
        }

        String textureValue = null;
        String textureSignature = null;
        PlayerProfile profile = owner.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) {
                textureValue = property.getValue();
                textureSignature = property.getSignature();
                break;
            }
        }

        this.buildCorpse(
                owner.getUniqueId(),
                owner.getName(),
                location,
                loot,
                textureValue,
                textureSignature,
                true,
                0L
        );
    }

    /**
     * Spawns a lootable corpse from a precomputed loot snapshot (used when an AFK stand-in for a disconnected raider is
     * killed or its reconnect grace expires — the owner is offline, so their gear was captured earlier). Resolves solid
     * ground and skips silently if the snapshot has no loot.
     */
    public void spawnCorpseFromSnapshot(
            UUID ownerId,
            String ownerName,
            Location deathLocation,
            ItemStack[] loot,
            String textureValue,
            String textureSignature
    ) {
        if (ownerId == null || deathLocation == null || deathLocation.getWorld() == null
                || BreachPlayerCorpseLayout.isEmpty(loot)) {
            return;
        }
        Optional<BreachCorpseGround.Anchor> groundOptional = BreachCorpseGround.resolve(deathLocation.clone());
        Location location = groundOptional
                .map(anchor -> this.spreadIfOverlapping(anchor.bodyLocation().clone()))
                .orElseGet(() -> this.spreadIfOverlapping(deathLocation.clone()));
        String safeName = ownerName == null ? "Raider" : ownerName;
        this.buildCorpse(ownerId, safeName, location, loot, textureValue, textureSignature, true, 0L);
    }

    /**
     * Lootable AI raider corpse: same packet body as player deaths, but no floating nametag, and auto-removed after
     * {@code despawnTicks} if not fully looted.
     *
     * @param skinProfileName Steve/Alex (or similar) profile name for default skin; textures may be null
     */
    public void spawnMobCorpse(
            Location deathLocation,
            ItemStack[] loot,
            String displayName,
            String skinProfileName,
            long despawnTicks
    ) {
        if (deathLocation == null || deathLocation.getWorld() == null || BreachPlayerCorpseLayout.isEmpty(loot)) {
            return;
        }
        Optional<BreachCorpseGround.Anchor> groundOptional = BreachCorpseGround.resolve(deathLocation.clone());
        Location location = groundOptional
                .map(anchor -> this.spreadIfOverlapping(anchor.bodyLocation().clone()))
                .orElseGet(() -> this.spreadIfOverlapping(deathLocation.clone()));
        String label = displayName == null || displayName.isBlank() ? "Raider" : displayName;
        String skin = skinProfileName == null || skinProfileName.isBlank() ? "Steve" : skinProfileName;
        this.buildCorpse(
                UUID.randomUUID(),
                label,
                location,
                loot,
                null,
                null,
                false,
                Math.max(0L, despawnTicks),
                skin
        );
    }

    private void buildCorpse(
            UUID ownerId,
            String ownerName,
            Location location,
            ItemStack[] loot,
            String textureValue,
            String textureSignature,
            boolean showNameLabel,
            long despawnTicks
    ) {
        this.buildCorpse(
                ownerId, ownerName, location, loot, textureValue, textureSignature, showNameLabel, despawnTicks, ownerName);
    }

    private void buildCorpse(
            UUID ownerId,
            String ownerName,
            Location location,
            ItemStack[] loot,
            String textureValue,
            String textureSignature,
            boolean showNameLabel,
            long despawnTicks,
            String profileName
    ) {
        UUID corpseId = UUID.randomUUID();
        PacketCorpseDisplay display;
        try {
            display = new PacketCorpseDisplay(
                    this.plugin,
                    profileName == null || profileName.isBlank() ? ownerName : profileName,
                    textureValue,
                    textureSignature,
                    location
            );
        } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("[Corpse] Visual spawn failed for " + ownerName + ": " + exception.getMessage());
            display = null;
        }

        World world = location.getWorld();
        int bodyEntityId = -1;
        UUID interactionId = null;
        UUID labelId = null;
        if (display != null) {
            this.revealDisplayToWorld(display, world, location);
            this.scheduleDisplayResync(display, world, location);
            // Click detection happens on the rendered body itself via inbound interaction packets, so there is no
            // separate Interaction entity (and thus no second hitbox next to the model).
            bodyEntityId = display.bodyEntityId();
            UUID capturedCorpseId = corpseId;
            this.interactionService().register(bodyEntityId, looter -> this.openLootById(capturedCorpseId, looter));
            if (showNameLabel) {
                labelId = this.spawnNameLabel(world, location, corpseId, ownerName);
            }
        } else {
            // Visual failed (very rare): fall back to a server-side Interaction entity so the loot is still reachable.
            Interaction hitbox = world.spawn(location.clone().add(0.0, -0.2, 0.0), Interaction.class, interaction -> {
                interaction.setInteractionWidth(1.0F);
                interaction.setInteractionHeight(0.8F);
                interaction.setResponsive(true);
                interaction.getPersistentDataContainer().set(this.corpseIdKey, PersistentDataType.STRING, corpseId.toString());
                interaction.getPersistentDataContainer().set(this.propTypeKey, PersistentDataType.STRING, PROP_TYPE);
            });
            interactionId = hitbox.getUniqueId();
            this.interactionToCorpse.put(interactionId, corpseId);
        }

        BreachPlayerCorpseState state = new BreachPlayerCorpseState(
                corpseId,
                ownerId,
                ownerName,
                world.getUID(),
                location.clone(),
                display,
                interactionId,
                bodyEntityId,
                labelId,
                loot
        );
        this.corpses.put(corpseId, state);
        if (despawnTicks > 0L) {
            Location expireAt = location.clone();
            this.scheduler.runGlobalLater(() -> this.scheduler.runAtLocation(
                    expireAt,
                    () -> {
                        if (this.corpses.containsKey(corpseId)) {
                            this.removeCorpse(corpseId);
                        }
                    }
            ), despawnTicks);
        }
        this.plugin.getLogger().info("[Corpse] Spawned corpse for " + ownerName + " at "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + (display == null ? " (loot only; visual failed)" : "")
                + (showNameLabel ? "" : " (no nametag)")
                + (despawnTicks > 0L ? " (despawn " + despawnTicks + "t)" : ""));
    }

    private PacketEntityInteractionService interactionService() {
        return this.core.packetEntityInteractionService();
    }

    private void openLootById(UUID corpseId, Player looter) {
        BreachPlayerCorpseState state = this.corpses.get(corpseId);
        if (state != null && !BreachPlayerCorpseLayout.isEmpty(state.loot())) {
            this.openLoot(looter, state);
        }
    }

    public BreachPlayerCorpseState findByInteraction(UUID interactionId) {
        UUID corpseId = this.interactionToCorpse.get(interactionId);
        return corpseId == null ? null : this.corpses.get(corpseId);
    }

    public BreachPlayerCorpseState find(UUID corpseId) {
        return corpseId == null ? null : this.corpses.get(corpseId);
    }

    public void openLoot(Player looter, BreachPlayerCorpseState state) {
        if (looter == null || state == null || this.isSpectator(looter)) {
            return;
        }
        Inventory inventory = this.liveInventories.computeIfAbsent(
                state.corpseId(),
                id -> {
                    BreachPlayerCorpseHolder holder = new BreachPlayerCorpseHolder(id);
                    Inventory created = org.bukkit.Bukkit.createInventory(
                            holder,
                            BreachPlayerCorpseLayout.INVENTORY_SIZE,
                            ExtractionTexts.miniMessage(null, "extraction.gui.corpse.title", state.ownerName())
                    );
                    holder.bindInventory(created);
                    BreachPlayerCorpseLayout.fill(created, state.loot());
                    return created;
                }
        );
        if (BreachPlayerCorpseLayout.isEmptyInventory(inventory)) {
            this.removeCorpse(state.corpseId());
            return;
        }
        BreachPlayerCorpseMenu menu = new BreachPlayerCorpseMenu(state, this, this.core.coreHotbarService());
        this.core.guiManager().openWithInventory(looter, menu, inventory, true);
    }

    /** Called from {@link BreachPlayerCorpseMenu} after the GUI framework closes the inventory. */
    public void afterCorpseMenuClosed(BreachPlayerCorpseState state) {
        if (state == null) {
            return;
        }
        Inventory inventory = this.liveInventories.get(state.corpseId());
        if (inventory != null) {
            BreachPlayerCorpseLayout.syncFromInventory(inventory, state.loot());
        }
        if (BreachPlayerCorpseLayout.isEmpty(state.loot())) {
            this.removeCorpse(state.corpseId());
        }
    }

    public void syncClosedInventory(BreachPlayerCorpseState state, Inventory inventory) {
        afterCorpseMenuClosed(state);
    }

    public void showCorpsesInWorld(Player player) {
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        UUID worldId = world.getUID();
        for (BreachPlayerCorpseState state : this.corpses.values()) {
            if (!state.worldId().equals(worldId) || state.display() == null) {
                continue;
            }
            if (this.isWithinViewDistance(player, state.location())) {
                state.display().resync(player);
            }
        }
    }

    public void hideCorpsesInChunk(Player player, Chunk chunk) {
        if (player == null || chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        UUID worldId = world.getUID();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        for (BreachPlayerCorpseState state : this.corpses.values()) {
            if (!state.worldId().equals(worldId)) {
                continue;
            }
            Location location = state.location();
            if ((location.getBlockX() >> 4) != chunkX || (location.getBlockZ() >> 4) != chunkZ) {
                continue;
            }
            if (state.display() != null) {
                state.display().destroy(player);
            }
        }
    }

    public void resyncCorpsesInChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        UUID worldId = world.getUID();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        for (BreachPlayerCorpseState state : this.corpses.values()) {
            if (!state.worldId().equals(worldId) || state.display() == null) {
                continue;
            }
            Location location = state.location();
            if ((location.getBlockX() >> 4) != chunkX || (location.getBlockZ() >> 4) != chunkZ) {
                continue;
            }
            world.getChunkAt(location).load(true);
            for (Player viewer : world.getPlayers()) {
                if (!viewer.isOnline() || !viewer.getWorld().equals(world)) {
                    continue;
                }
                if (this.isWithinViewDistance(viewer, location)) {
                    state.display().resync(viewer);
                }
            }
        }
    }

    public void hideCorpsesFrom(Player player) {
        if (player == null) {
            return;
        }
        for (BreachPlayerCorpseState state : this.corpses.values()) {
            if (state.display() != null) {
                state.display().destroy(player);
            }
        }
    }

    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        for (UUID corpseId : this.corpses.keySet().toArray(UUID[]::new)) {
            BreachPlayerCorpseState state = this.corpses.get(corpseId);
            if (state != null && state.worldId().equals(worldId)) {
                this.removeCorpse(corpseId);
            }
        }
    }

    public void removeCorpse(UUID corpseId) {
        BreachPlayerCorpseState state = this.corpses.remove(corpseId);
        if (state == null) {
            this.liveInventories.remove(corpseId);
            return;
        }
        if (state.bodyEntityId() >= 0) {
            this.interactionService().unregister(state.bodyEntityId());
        }
        this.closeAndDropLiveInventory(corpseId);
        if (state.interactionId() != null) {
            this.interactionToCorpse.remove(state.interactionId());
            org.bukkit.entity.Entity entity = this.plugin.getServer().getEntity(state.interactionId());
            if (entity != null) {
                entity.remove();
            }
        }
        if (state.display() != null) {
            for (Player online : this.plugin.getServer().getOnlinePlayers()) {
                state.display().destroy(online);
            }
        }
        this.removeNameLabel(state.labelId());
    }

    /** Floating "<player>'s body" label above the corpse (real entity; the body itself has its nametag hidden). */
    private UUID spawnNameLabel(World world, Location bodyLocation, UUID corpseId, String ownerName) {
        try {
            Location labelLocation = bodyLocation.clone().add(0.0, 0.55, 0.0);
            TextDisplay label = world.spawn(labelLocation, TextDisplay.class, display -> {
                display.text(ExtractionTexts.miniMessage(null, "extraction.gui.corpse.title", ownerName));
                display.setBillboard(Billboard.CENTER);
                display.setPersistent(false);
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(64, 0, 0, 0));
                display.setShadowed(true);
                display.getPersistentDataContainer().set(this.corpseIdKey, PersistentDataType.STRING, corpseId.toString());
                display.getPersistentDataContainer().set(this.propTypeKey, PersistentDataType.STRING, PROP_TYPE + "_LABEL");
            });
            return label.getUniqueId();
        } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("[Corpse] Name label spawn failed for " + ownerName + ": " + exception.getMessage());
            return null;
        }
    }

    private void removeNameLabel(UUID labelId) {
        if (labelId == null) {
            return;
        }
        org.bukkit.entity.Entity label = this.plugin.getServer().getEntity(labelId);
        if (label != null) {
            this.scheduler.runAtEntity(label, label::remove);
        }
    }

    private void closeAndDropLiveInventory(UUID corpseId) {
        Inventory inventory = this.liveInventories.remove(corpseId);
        if (inventory == null) {
            return;
        }
        for (Player viewer : this.plugin.getServer().getOnlinePlayers()) {
            // Defer one tick: this can run during a viewer's own InventoryCloseEvent, and closing again mid-event
            // would re-enter the close handler. Next tick the closing viewer no longer holds the corpse inventory.
            this.scheduler.runOnPlayerLater(viewer, () -> {
                InventoryHolder holder = viewer.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof BreachPlayerCorpseHolder corpseHolder && corpseId.equals(corpseHolder.corpseId())) {
                    viewer.closeInventory();
                }
            }, 1L);
        }
    }

    private void revealDisplayToWorld(PacketCorpseDisplay display, World world, Location location) {
        if (display == null || world == null || location == null) {
            return;
        }
        world.getChunkAt(location).load(true);
        for (Player viewer : world.getPlayers()) {
            if (viewer.isOnline() && viewer.getWorld().equals(world) && this.isWithinViewDistance(viewer, location)) {
                display.resync(viewer);
            }
        }
    }

    private void scheduleDisplayResync(PacketCorpseDisplay display, World world, Location location) {
        Location revealLocation = location.clone();
        for (long delayTicks : new long[] {2L, 10L, 40L}) {
            this.scheduler.runGlobalLater(() -> this.scheduler.runAtLocation(
                    revealLocation,
                    () -> this.revealDisplayToWorld(display, world, revealLocation)
            ), delayTicks);
        }
    }

    /** Minimum spacing (blocks, squared) two corpses should keep so their hitboxes stay individually clickable. */
    private static final double CORPSE_SPREAD_MIN_DISTANCE_SQ = 0.9 * 0.9;

    private Location spreadIfOverlapping(Location base) {
        if (base == null || base.getWorld() == null || !this.hasNearbyCorpse(base)) {
            return base;
        }
        for (int ring = 1; ring <= 3; ring++) {
            double radius = 0.85 * ring;
            for (int step = 0; step < 8; step++) {
                double angle = (Math.PI / 4.0) * step;
                Location candidate = base.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
                if (!this.hasNearbyCorpse(candidate)) {
                    return candidate;
                }
            }
        }
        return base;
    }

    private boolean hasNearbyCorpse(Location location) {
        for (BreachPlayerCorpseState state : this.corpses.values()) {
            Location other = state.location();
            if (other != null
                    && other.getWorld() != null
                    && other.getWorld().equals(location.getWorld())
                    && other.distanceSquared(location) < CORPSE_SPREAD_MIN_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinViewDistance(Player viewer, Location target) {
        if (viewer == null || target == null || !viewer.getWorld().equals(target.getWorld())) {
            return false;
        }
        int viewBlocks = Math.max(16, viewer.getViewDistance()) * 16;
        return viewer.getLocation().distanceSquared(target) <= (long) viewBlocks * viewBlocks;
    }

    private ItemStack[] mergeEarlySnapshot(UUID ownerId, ItemStack[] loot) {
        ItemStack[] snapshot = this.earlyLootSnapshots.remove(ownerId);
        if (snapshot == null) {
            return loot;
        }
        ItemStack[] merged = loot == null ? new ItemStack[BreachPlayerCorpseLayout.INVENTORY_SIZE] : loot;
        for (int slot = 0; slot < Math.min(snapshot.length, merged.length); slot++) {
            ItemStack item = snapshot[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (merged[slot] == null || merged[slot].getType().isAir()) {
                merged[slot] = item.clone();
            }
        }
        return merged;
    }

    public record BreachPlayerCorpseState(
            UUID corpseId,
            UUID ownerId,
            String ownerName,
            UUID worldId,
            Location location,
            PacketCorpseDisplay display,
            UUID interactionId,
            int bodyEntityId,
            UUID labelId,
            ItemStack[] loot
    ) {
        public BreachPlayerCorpseState {
            Objects.requireNonNull(corpseId, "corpseId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(ownerName, "ownerName");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(location, "location");
            // interactionId is null when the corpse is clicked via packets (the common case); only the rare
            // visual-failed fallback uses a real Interaction entity.
            loot = loot == null ? new ItemStack[BreachPlayerCorpseLayout.INVENTORY_SIZE] : loot;
        }
    }
}
