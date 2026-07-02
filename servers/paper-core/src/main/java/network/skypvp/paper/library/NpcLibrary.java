package network.skypvp.paper.library;

import network.skypvp.shared.ServerTextUtil;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.packet.PacketGlowTeams;
import network.skypvp.paper.library.packet.PacketFakeMob;
import network.skypvp.paper.library.packet.PacketFakePlayer;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.NpcDefinition;
import network.skypvp.paper.model.WorldPoint;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.Team.Option;
import org.bukkit.scoreboard.Team.OptionStatus;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class NpcLibrary implements Listener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final PaperCorePlugin plugin;
   private final NamespacedKey npcIdKey;
   private final NamespacedKey actionTypeKey;
   private final NamespacedKey actionDataKey;
   private final NamespacedKey facePlayerKey;
   private final NamespacedKey blockPropIdKey;
   private final NamespacedKey blockPropTypeKey;
   private final NamespacedKey blockPropAnchorKey;
   private PlatformTask facePlayerTask;
   private PlatformTask rainbowTask;
   private final Map<String, PacketFakePlayer> fakePlayers = new HashMap<>();
   private final Map<String, PacketFakeMob> fakeMobs = new HashMap<>();
   private final Map<String, LivingEntity> activeEntities = new ConcurrentHashMap<>();
   private final Map<String, Entity> activeNonPlayerEntities = new ConcurrentHashMap<>();
   private final Map<String, Interaction> activeInteractions = new ConcurrentHashMap<>();
   private final Map<String, NpcBlockPropHandle> activeBlockProps = new ConcurrentHashMap<>();
   private final Map<String, StoredBlockProp> blockPropDefinitions = new ConcurrentHashMap<>();
   private final Map<String, NpcDefinition> activeDefinitions = new ConcurrentHashMap<>();
   private final Map<String, Integer> rainbowEntries = new ConcurrentHashMap<>();
   private static final NamedTextColor[] RAINBOW_COLORS = new NamedTextColor[]{
      NamedTextColor.RED,
      NamedTextColor.GOLD,
      NamedTextColor.YELLOW,
      NamedTextColor.GREEN,
      NamedTextColor.AQUA,
      NamedTextColor.BLUE,
      NamedTextColor.LIGHT_PURPLE,
      NamedTextColor.DARK_PURPLE
   };
   private PlatformTask distanceTrackerTask;
   private final NpcDistanceIndex distanceIndex = new NpcDistanceIndex();
   private final Map<UUID, Long> lastInteractMs = new ConcurrentHashMap<>();
   /** Tracks Bukkit showEntity/hideEntity per viewer so we don't re-send every distance tick. */
   private final Set<String> entityShownToViewer = ConcurrentHashMap.newKeySet();

   public NpcLibrary(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.npcIdKey = new NamespacedKey(plugin, "layout_npc_id");
      this.actionTypeKey = new NamespacedKey(plugin, "layout_npc_action_type");
      this.actionDataKey = new NamespacedKey(plugin, "layout_npc_action_data");
      this.facePlayerKey = new NamespacedKey(plugin, "layout_npc_face_player");
      this.blockPropIdKey = new NamespacedKey(plugin, "block_prop_id");
      this.blockPropTypeKey = new NamespacedKey(plugin, "block_prop_type");
      this.blockPropAnchorKey = new NamespacedKey(plugin, "block_prop_anchor");
      this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
      this.startFacePlayerTask();
      this.startRainbowTask();
      this.startDistanceTrackerTask();
   }

   @EventHandler
   public void onChunkLoad(ChunkLoadEvent event) {
      Chunk chunk = event.getChunk();
      World world = chunk.getWorld();
      String worldName = world.getName();
      int cx = chunk.getX();
      int cz = chunk.getZ();
      boolean touchedNpc = false;

      for (NpcDefinition def : this.activeDefinitions.values()) {
         if (def.location != null && worldName.equals(def.location.world)) {
            int defCx = NumberConversions.floor(def.location.x) >> 4;
            int defCz = NumberConversions.floor(def.location.z) >> 4;
            if (defCx == cx && defCz == cz) {
               touchedNpc = true;
               String id = def.id.toLowerCase(Locale.ROOT);
               Location location = this.toLocation(world, def.location);
               if ("PLAYER".equalsIgnoreCase(def.entityType)) {
                  Interaction existing = this.activeInteractions.get(id);
                  if (existing == null || !existing.isValid() || existing.isDead()) {
                     this.cleanSpawnArea(location, 1.0);
                     existing = (Interaction)this.spawnNpc(world, def, location);
                  }

                  if (existing != null) {
                     List<Scoreboard> boards = new ArrayList<>();
                     boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
                     for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()) {
                           boards.add(p.getScoreboard());
                        }
                     }
                     this.applyMetadata(existing, def, boards);
                     PacketFakePlayer fake = this.fakePlayers.get(id);
                     if (fake == null || !fake.matchesGlow(def.glow, def.glowColor)) {
                        if (fake != null) {
                           for (Player viewer : Bukkit.getOnlinePlayers()) {
                              fake.destroy(viewer);
                           }
                        }
                        String profileName = id.length() > 16 ? id.substring(0, 16) : id;
                        fake = new PacketFakePlayer(this.plugin, profileName, def.skinUrl, def.skinSignature, location, def.glow, def.glowColor);
                        this.fakePlayers.put(id, fake);
                     }
                     this.syncFakePlayerGlowTeams(fake, def);
                     this.plugin.platform().runForNearbyPlayers(location, NpcDistanceIndex.VISIBILITY_RADIUS_SQ, fake::resync);
                  }
               } else {
                  Interaction existingHitbox = this.activeInteractions.get(id);
                  if (existingHitbox == null || !existingHitbox.isValid() || existingHitbox.isDead()) {
                     this.cleanSpawnArea(location, 1.0);
                     this.spawnInteractionHitbox(world, def, location);
                  }
                  this.removeMobDisplayEntity(id);
                  this.configureMobNpc(id, def, location);
               }
            }
         }
      }

      // Non-persistent NPC interactions are removed when their chunk unloads and re-spawned here on
      // reload. The distance index caches entity references, so without this re-sync it keeps pointing
      // at the now-dead entity (forEachNear skips dead entities => packet NPC body never shows again).
      if (touchedNpc) {
         this.rebuildDistanceIndex(world);
      }

      for (StoredBlockProp definition : this.blockPropDefinitions.values()) {
         if (definition.blockAnchor().getWorld() == null || !worldName.equals(definition.blockAnchor().getWorld().getName())) {
            continue;
         }
         int propCx = definition.blockAnchor().getBlockX() >> 4;
         int propCz = definition.blockAnchor().getBlockZ() >> 4;
         if (propCx == cx && propCz == cz) {
            this.ensureBlockProp(definition.id());
         }
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.applyViewerMetadata(player);

      this.resyncViewer(player);
   }

   public void resyncViewer(Player player) {
      if (player == null || !player.isOnline()) {
         return;
      }
      UUID viewerId = player.getUniqueId();
      this.entityShownToViewer.removeIf(key -> key.startsWith(viewerId + ":"));
      this.destroyAllFakesFor(player);
      this.applyViewerMetadata(player);
      Location playerLocation = player.getLocation();
      World playerWorld = player.getWorld();
      this.distanceIndex.forEachNear(player, playerLocation, playerWorld, (id, entity) ->
         this.trackEntityVisibility(player, playerLocation, playerWorld, id, entity)
      );
      this.trackPacketNpcsByDefinition(player, playerLocation, playerWorld);
      this.forceResyncPacketPlayersForViewer(player, playerLocation, playerWorld);
      this.forceResyncPacketMobsForViewer(player, playerLocation, playerWorld);
   }

   /**
    * Re-applies packet glow teams for nearby fake player bodies. Packet teams can be dropped client-side
    * during decoration reloads, teleports, or sidebar setup.
    */
   public void refreshViewerPacketGlow(Player player) {
      if (player == null || !player.isOnline()) {
         return;
      }
      Location playerLocation = player.getLocation();
      World playerWorld = player.getWorld();
      this.refreshViewerPacketGlow(player, playerLocation, playerWorld);
   }

   private void refreshViewerPacketGlow(Player player, Location playerLocation, World playerWorld) {
      if (player == null || playerLocation == null || playerWorld == null) {
         return;
      }
      String worldName = playerWorld.getName();
      for (Entry<String, NpcDefinition> entry : this.activeDefinitions.entrySet()) {
         String id = entry.getKey();
         PacketFakePlayer fake = this.fakePlayers.get(id);
         if (fake == null) {
            continue;
         }
         NpcDefinition def = entry.getValue();
         if (def == null || def.location == null || !worldName.equals(def.location.world)) {
            continue;
         }
         Location npcLoc = this.toLocation(playerWorld, def.location);
         if (playerLocation.distanceSquared(npcLoc) <= NpcDistanceIndex.VISIBILITY_RADIUS_SQ) {
            fake.refreshGlow(player);
         }
      }
   }

   /** Forces a glow refresh for packet player bodies after teleports / decoration reloads. */
   private void forceResyncPacketPlayersForViewer(Player player, Location playerLocation, World playerWorld) {
      this.refreshViewerPacketGlow(player, playerLocation, playerWorld);
   }

   /** Forces a destroy+respawn cycle for packet mob bodies after teleports / decoration reloads. */
   private void forceResyncPacketMobsForViewer(Player player, Location playerLocation, World playerWorld) {
      if (player == null || playerLocation == null || playerWorld == null) {
         return;
      }
      String worldName = playerWorld.getName();
      for (Entry<String, NpcDefinition> entry : this.activeDefinitions.entrySet()) {
         String id = entry.getKey();
         PacketFakeMob fakeMob = this.fakeMobs.get(id);
         if (fakeMob == null) {
            continue;
         }
         NpcDefinition def = entry.getValue();
         if (def == null || def.location == null || !worldName.equals(def.location.world)) {
            continue;
         }
         Location npcLoc = this.toLocation(playerWorld, def.location);
         if (playerLocation.distanceSquared(npcLoc) <= NpcDistanceIndex.VISIBILITY_RADIUS_SQ) {
            fakeMob.resync(player);
         }
      }
   }

   /** Tear down packet NPCs for every online player before a full decoration reload. */
   public void clearAllPacketViewers() {
      for (Player player : Bukkit.getOnlinePlayers()) {
         this.destroyAllFakesFor(player);
      }
      this.fakePlayers.clear();
      this.fakeMobs.clear();
   }

   /** Re-send packet NPCs to every online player after a full decoration reload. */
   public void resyncAllViewers() {
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (!player.isOnline()) {
            continue;
         }
         this.plugin.platform().runOnPlayerLater(player, () -> this.resyncViewer(player), 5L);
      }
   }

   public void applyViewerMetadata(Player player) {
      List<Scoreboard> boards = new ArrayList<>();
      boards.add(player.getScoreboard());
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      if (player.getScoreboard() != main) {
         boards.add(main);
      }

      for (Entry<String, LivingEntity> entry : this.activeEntities.entrySet()) {
         String id = entry.getKey();
         LivingEntity entity = entry.getValue();
         NpcDefinition def = this.activeDefinitions.get(id);
         if (def != null) {
            this.applyMetadata(entity, def, boards);
         }
      }

      for (Entry<String, Interaction> entry : this.activeInteractions.entrySet()) {
         String id = entry.getKey();
         Interaction entity = entry.getValue();
         NpcDefinition def = this.activeDefinitions.get(id);
         if (def != null) {
            this.applyMetadata(entity, def, boards);
         }
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      UUID playerId = event.getPlayer().getUniqueId();
      this.entityShownToViewer.removeIf(key -> key.startsWith(playerId + ":"));
      this.destroyAllFakesFor(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
      this.plugin.platform().runOnPlayerLater(event.getPlayer(), () -> this.resyncViewer(event.getPlayer()), 2L);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerTeleport(PlayerTeleportEvent event) {
      if (event.getFrom().getWorld().equals(event.getTo().getWorld())
         && event.getFrom().distanceSquared(event.getTo()) < 4.0) {
         return;
      }
      this.plugin.platform().runOnPlayerLater(event.getPlayer(), () -> this.resyncViewer(event.getPlayer()), 2L);
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onNpcDamage(EntityDamageByEntityEvent event) {
      Entity damaged = event.getEntity();
      String npcId = this.readNpcId(damaged);
      if (npcId != null && !npcId.isBlank()) {
         event.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInteractAt(PlayerInteractAtEntityEvent event) {
      if (event.getHand() == EquipmentSlot.HAND && this.handleInteract(event.getPlayer(), event.getRightClicked())) {
         event.setCancelled(true);
      }
   }

   private void startRainbowTask() {
      if (this.rainbowTask == null) {
         this.rainbowTask = this.plugin.platform().runGlobalTimer(this::tickRainbowNpcTeams, 20L, 5L);
      }
   }

   private void tickRainbowNpcTeams() {
      if (this.plugin.platform().isFolia() || this.rainbowEntries.isEmpty()) {
         return;
      }
      Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
      List<Scoreboard> boards = new ArrayList<>();
      boards.add(mainBoard);

      for (Player p : Bukkit.getOnlinePlayers()) {
         if (p.getScoreboard() != mainBoard) {
            boards.add(p.getScoreboard());
         }
      }

      for (Entry<String, Integer> entry : this.rainbowEntries.entrySet()) {
         String profileName = entry.getKey();
         int nextIdx = (entry.getValue() + 1) % RAINBOW_COLORS.length;
         entry.setValue(nextIdx);
         NamedTextColor color = RAINBOW_COLORS[nextIdx];
         String teamName = "npc_rainbow";

         for (Scoreboard b : boards) {
            Team t = b.getTeam(teamName);
            if (t == null) {
               t = b.registerNewTeam(teamName);
               t.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
            }

            t.color(color);
            if (!t.hasEntry(profileName)) {
               t.addEntry(profileName);
            }
         }
      }
   }

   private void startFacePlayerTask() {
      if (this.facePlayerTask == null) {
         this.facePlayerTask = this.plugin.platform().runGlobalTimer(this::tickFacePlayerNpcs, 20L, 5L);
      }
   }

   private void tickFacePlayerNpcs() {
      // Driven off the in-memory definitions (not live entities) so the global timer never touches entity
      // state off its owning region thread on Folia. Each NPC's rotation work is dispatched to the region
      // that owns its location.
      for (Entry<String, NpcDefinition> entry : this.activeDefinitions.entrySet()) {
         NpcDefinition def = entry.getValue();
         if (def == null || !def.facePlayer || def.location == null || def.location.world == null) {
            continue;
         }
         World world = Bukkit.getWorld(def.location.world);
         if (world == null) {
            continue;
         }
         String id = entry.getKey();
         Location npcLoc = this.toLocation(world, def.location);
         if (this.plugin.platform().isFolia()) {
            this.plugin.platform().runAtLocation(npcLoc, () -> this.tickFacePlayerForNpc(id, npcLoc));
         } else {
            this.tickFacePlayerForNpc(id, npcLoc);
         }
      }
   }

   /** Runs on the region thread that owns {@code npcLoc}. */
   private void tickFacePlayerForNpc(String id, Location npcLoc) {
      World world = npcLoc.getWorld();
      if (world == null) {
         return;
      }
      PacketFakePlayer fake = this.fakePlayers.get(id);
      PacketFakeMob fakeMob = this.fakeMobs.get(id);

      if (fake != null || fakeMob != null) {
         Location eyeLoc = npcLoc.clone().add(0.0, 1.62, 0.0);
         for (Player viewer : world.getPlayers()) {
            if (!viewer.isOnline() || viewer.getLocation().distanceSquared(npcLoc) >= 1600.0) {
               continue;
            }
            Vector dir = viewer.getEyeLocation().toVector().subtract(eyeLoc.toVector());
            Location look = npcLoc.clone();
            look.setDirection(dir);
            if (fake != null) {
               fake.updateRotation(viewer, look);
            }
            if (fakeMob != null) {
               fakeMob.updateRotation(viewer, look);
            }
         }
         return;
      }

      LivingEntity living = this.activeEntities.get(id);
      if (living == null || !living.isValid() || living.isDead()) {
         return;
      }
      Player closest = null;
      double closestDistSq = 100.0;
      for (Player px : world.getPlayers()) {
         double dSq = px.getLocation().distanceSquared(npcLoc);
         if (dSq < closestDistSq) {
            closestDistSq = dSq;
            closest = px;
         }
      }
      if (closest != null) {
         Vector dir = closest.getEyeLocation().toVector().subtract(living.getEyeLocation().toVector());
         Location loc = living.getLocation();
         loc.setDirection(dir);
         living.setRotation(loc.getYaw(), loc.getPitch());
      }
   }

   private void startDistanceTrackerTask() {
      if (this.distanceTrackerTask == null) {
         this.distanceTrackerTask = this.plugin.platform().runGlobalTimer(this::tickNpcDistanceVisibility, 20L, 20L);
      }
   }

   private void tickNpcDistanceVisibility() {
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (this.plugin.platform().isFolia()) {
            this.plugin.platform().runOnPlayer(player, () -> this.trackNpcDistanceForPlayer(player));
         } else {
            this.trackNpcDistanceForPlayer(player);
         }
      }
   }

   private void trackNpcDistanceForPlayer(Player player) {
      Location pLoc = player.getLocation();
      World pWorld = player.getWorld();
      this.distanceIndex.forEachNear(player, pLoc, pWorld,
         (id, entity) -> this.trackEntityVisibility(player, pLoc, pWorld, id, entity));
      this.trackPacketNpcsByDefinition(player, pLoc, pWorld);
   }

   private void trackEntityVisibility(Player player, Location pLoc, World pWorld, String id, Entity entity) {
      boolean inRange = entity != null
         && entity.isValid()
         && !entity.isDead()
         && entity.getWorld().equals(pWorld)
         && pLoc.distanceSquared(entity.getLocation()) <= NpcDistanceIndex.VISIBILITY_RADIUS_SQ;

      PacketFakePlayer fake = this.fakePlayers.get(id);
      PacketFakeMob fakeMob = this.fakeMobs.get(id);
      String entityVisibilityKey = player.getUniqueId() + ":" + id;
      if (inRange) {
         if (fake != null) {
            fake.showTo(player);
         }
         if (fakeMob != null) {
            fakeMob.showTo(player);
         }

         if (entity != null && !"PLAYER".equals(entity.getType().name()) && fakeMob == null) {
            if (this.entityShownToViewer.add(entityVisibilityKey)) {
               player.showEntity(this.plugin, entity);
            }
            this.applyBlockPropGlowPacket(player, entity);
         }

         this.plugin.holographicLibrary().showTo(player, id + "_holo");
      } else {
         if (fake != null) {
            fake.destroy(player);
         }
         if (fakeMob != null) {
            fakeMob.destroy(player);
         }

         if (entity != null && !"PLAYER".equals(entity.getType().name()) && fakeMob == null) {
            if (this.entityShownToViewer.remove(entityVisibilityKey)) {
               player.hideEntity(this.plugin, entity);
            }
         }

         this.plugin.holographicLibrary().hideFrom(player, id + "_holo");
      }
   }

   private void applyBlockPropGlowPacket(Player player, Entity entity) {
      if (!PacketGlowTeams.usePacketGlow(this.plugin) || player == null || entity == null) {
         return;
      }
      String propId = this.readPdc(entity, this.blockPropIdKey);
      if (propId == null || propId.isBlank()) {
         return;
      }
      StoredBlockProp definition = this.blockPropDefinitions.get(propId.toLowerCase(Locale.ROOT));
      if (definition == null) {
         return;
      }
      PacketGlowTeams.applyGlow(
              this.plugin,
              player,
              entity.getUniqueId().toString(),
              definition.glow(),
              definition.glowColor()
      );
   }

   /**
    * Packet NPC visibility is normally driven off the in-world {@link Interaction} hitbox tracked by the
    * distance index. That hitbox can be momentarily dead/unloaded (e.g. right after teleporting back into a
    * world while its chunk is still respawning), which would skip the packet body and leave the NPC invisible
    * until the next chunk reload. Fake players/mobs are packet-based and don't actually require a live entity,
    * so we additionally drive their visibility straight off the in-memory definition location. This is
    * idempotent with {@link #trackEntityVisibility} (showTo/destroy are no-ops when already in the right state)
    * and must run AFTER it so it wins for packet NPCs whose hitbox is temporarily dead.
    */
   private void trackPacketNpcsByDefinition(Player player, Location pLoc, World pWorld) {
      if (player == null || pLoc == null || pWorld == null) {
         return;
      }
      String worldName = pWorld.getName();
      for (Entry<String, NpcDefinition> entry : this.activeDefinitions.entrySet()) {
         String id = entry.getKey();
         PacketFakePlayer fake = this.fakePlayers.get(id);
         PacketFakeMob fakeMob = this.fakeMobs.get(id);
         if (fake == null && fakeMob == null) {
            continue;
         }
         NpcDefinition def = entry.getValue();
         if (def == null || def.location == null || !worldName.equals(def.location.world)) {
            if (fake != null) {
               fake.destroy(player);
            }
            if (fakeMob != null) {
               fakeMob.destroy(player);
            }
            continue;
         }
         Location npcLoc = this.toLocation(pWorld, def.location);
         boolean inRange = pLoc.distanceSquared(npcLoc) <= NpcDistanceIndex.VISIBILITY_RADIUS_SQ;
         if (inRange) {
            if (fake != null) {
               fake.showTo(player);
            }
            if (fakeMob != null) {
               fakeMob.showTo(player);
            }
            this.plugin.holographicLibrary().showTo(player, id + "_holo");
         } else {
            if (fake != null) {
               fake.destroy(player);
            }
            if (fakeMob != null) {
               fakeMob.destroy(player);
            }
            this.plugin.holographicLibrary().hideFrom(player, id + "_holo");
         }
      }
   }

   public NamespacedKey blockPropTypeKey() {
      return this.blockPropTypeKey;
   }

   public NamespacedKey blockPropAnchorKey() {
      return this.blockPropAnchorKey;
   }

   public Optional<NpcBlockPropHandle> findBlockProp(String id) {
      if (id == null || id.isBlank()) {
         return Optional.empty();
      }
      return Optional.ofNullable(this.activeBlockProps.get(id.toLowerCase(Locale.ROOT)));
   }

   public Optional<Location> readBlockPropAnchor(Entity entity) {
      if (entity == null || entity.getWorld() == null) {
         return Optional.empty();
      }
      Long anchor = entity.getPersistentDataContainer().get(this.blockPropAnchorKey, PersistentDataType.LONG);
      if (anchor == null) {
         return Optional.empty();
      }
      return Optional.of(this.locationFromAnchorKey(entity.getWorld(), anchor));
   }

   public NpcBlockPropHandle spawnBlockProp(
      String id,
      Location blockAnchor,
      Material material,
      String propType,
      long anchorKey,
      boolean glow,
      String glowColor
   ) {
      return this.spawnBlockProp(id, blockAnchor, material, propType, anchorKey, glow, glowColor, true);
   }

   public NpcBlockPropHandle spawnBlockProp(
      String id,
      Location blockAnchor,
      Material material,
      String propType,
      long anchorKey,
      boolean glow,
      String glowColor,
      boolean persistent
   ) {
      if (id == null || id.isBlank() || blockAnchor == null || blockAnchor.getWorld() == null || material == null) {
         return null;
      }
      String normalizedId = id.toLowerCase(Locale.ROOT);
      StoredBlockProp definition = new StoredBlockProp(
         normalizedId,
         blockAnchor.clone(),
         material.createBlockData(),
         propType,
         anchorKey,
         glow,
         glowColor,
         persistent
      );
      this.blockPropDefinitions.put(normalizedId, definition);
      return this.spawnBlockPropEntities(definition);
   }

   public boolean ensureBlockProp(String id) {
      if (id == null || id.isBlank()) {
         return false;
      }
      StoredBlockProp definition = this.blockPropDefinitions.get(id.toLowerCase(Locale.ROOT));
      if (definition == null) {
         return false;
      }
      NpcBlockPropHandle existing = this.activeBlockProps.get(definition.id());
      if (existing != null && existing.isValid()) {
         return true;
      }
      return this.spawnBlockPropEntities(definition) != null;
   }

   private NpcBlockPropHandle spawnBlockPropEntities(StoredBlockProp definition) {
      this.removeBlockPropEntities(definition.id());
      World world = definition.blockAnchor().getWorld();
      if (world == null) {
         return null;
      }
      Location center = definition.blockAnchor().clone().add(0.5, 0.0, 0.5);
      BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
         entity.setBlock(definition.blockData().clone());
         entity.setBillboard(Billboard.FIXED);
         entity.setPersistent(definition.persistent());
         entity.setInvulnerable(true);
         entity.setSilent(true);
         entity.setGravity(false);
         entity.setTeleportDuration(0);
         entity.setTransformation(
            new Transformation(
               new Vector3f(-0.5F, 0.0F, -0.5F),
               new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F),
               new Vector3f(1.0F, 1.0F, 1.0F),
               new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)
            )
         );
      });
      // Interaction hitboxes are bottom-anchored: entity Y is the floor of the box, not its center.
      Interaction interaction = world.spawn(center, Interaction.class, hitbox -> {
         hitbox.setInteractionWidth(1.0F);
         hitbox.setInteractionHeight(0.875F);
         hitbox.setResponsive(true);
         hitbox.setPersistent(definition.persistent());
         hitbox.setInvulnerable(true);
         hitbox.setSilent(true);
      });
      this.writeBlockPropMetadata(display, definition.id(), definition.propType(), definition.anchorKey());
      this.writeBlockPropMetadata(interaction, definition.id(), definition.propType(), definition.anchorKey());
      this.applyBlockPropGlow(display, definition.blockAnchor(), definition.glow(), definition.glowColor());
      NpcBlockPropHandle handle = new NpcBlockPropHandle(definition.id(), definition.blockAnchor(), display, interaction);
      this.activeBlockProps.put(definition.id(), handle);
      this.distanceIndex.registerBlockProp(
              definition.id(),
              definition.blockAnchor(),
              display,
              interaction
      );
      return handle;
   }

   private void removeBlockPropEntities(String id) {
      String normalizedId = id.toLowerCase(Locale.ROOT);
      this.distanceIndex.unregisterBlockProp(normalizedId);
      NpcBlockPropHandle existing = this.activeBlockProps.remove(normalizedId);
      if (existing != null) {
         existing.remove();
      }
   }

   public void updateBlockPropGlow(String id, boolean glow, String glowColor) {
      if (id == null || id.isBlank()) {
         return;
      }
      String normalized = id.toLowerCase(Locale.ROOT);
      StoredBlockProp existing = this.blockPropDefinitions.get(normalized);
      if (existing != null) {
         this.updateBlockPropAppearance(normalized, existing.blockData(), glow, glowColor);
         return;
      }
      this.findBlockProp(id).ifPresent(handle -> {
         if (handle.display() != null) {
            this.applyBlockPropGlow(handle.display(), handle.blockAnchor(), glow, glowColor);
         }
      });
   }

   public void updateBlockPropAppearance(String id, BlockData blockData, boolean glow, String glowColor) {
      this.updateBlockPropAppearance(id, blockData, glow, glowColor, null);
   }

   public void updateBlockPropAppearance(
           String id,
           BlockData blockData,
           boolean glow,
           String glowColor,
           Transformation transformation
   ) {
      if (id == null || id.isBlank() || blockData == null) {
         return;
      }
      String normalized = id.toLowerCase(Locale.ROOT);
      StoredBlockProp existing = this.blockPropDefinitions.get(normalized);
      if (existing != null) {
         this.blockPropDefinitions.put(
                 normalized,
                 new StoredBlockProp(
                         existing.id(),
                         existing.blockAnchor(),
                         blockData.clone(),
                         existing.propType(),
                         existing.anchorKey(),
                         glow,
                         glowColor,
                         existing.persistent()
                 )
         );
      }
      this.findBlockProp(id).ifPresent(handle -> {
         BlockDisplay display = handle.display();
         if (display != null && display.isValid()) {
            display.setBlock(blockData.clone());
            if (transformation != null) {
               display.setTransformation(transformation);
            }
            this.applyBlockPropGlow(display, handle.blockAnchor(), glow, glowColor);
         }
      });
   }

   public void removeBlockProp(String id) {
      if (id == null || id.isBlank()) {
         return;
      }
      String normalizedId = id.toLowerCase(Locale.ROOT);
      this.blockPropDefinitions.remove(normalizedId);
      this.removeBlockPropEntities(normalizedId);
   }

   public void removeBlockPropsForWorld(World world, String idPrefix) {
      if (world == null) {
         return;
      }
      String prefix = idPrefix == null ? "" : idPrefix.toLowerCase(Locale.ROOT);
      for (String id : new ArrayList<>(this.activeBlockProps.keySet())) {
         NpcBlockPropHandle handle = this.activeBlockProps.get(id);
         if (handle == null || handle.blockAnchor().getWorld() == null || !handle.blockAnchor().getWorld().equals(world)) {
            continue;
         }
         if (prefix.isBlank() || id.startsWith(prefix)) {
            this.removeBlockProp(id);
         }
      }
   }

   private void writeBlockPropMetadata(Entity entity, String id, String propType, long anchorKey) {
      PersistentDataContainer pdc = entity.getPersistentDataContainer();
      pdc.set(this.blockPropIdKey, PersistentDataType.STRING, id);
      pdc.set(this.blockPropTypeKey, PersistentDataType.STRING, propType == null ? "GENERIC" : propType);
      pdc.set(this.blockPropAnchorKey, PersistentDataType.LONG, anchorKey);
   }

   private void applyBlockPropGlow(Entity entity, Location anchor, boolean glow, String glowColor) {
      if (entity == null) {
         return;
      }
      if (this.plugin.platform().isFolia()) {
         if (anchor == null) {
            return;
         }
         this.plugin.platform().runAtLocation(anchor, () -> {
            entity.setGlowing(glow);
            String entry = entity.getUniqueId().toString();
            this.plugin.platform().runForNearbyPlayers(
                    entity.getLocation(),
                    NpcDistanceIndex.VISIBILITY_RADIUS_SQ,
                    player -> PacketGlowTeams.applyGlow(this.plugin, player, entry, glow, glowColor)
            );
         });
         return;
      }
      this.plugin.platform().runScoreboard(() -> this.applyBlockPropGlowSync(entity, glow, glowColor));
   }

   private void applyBlockPropGlowSync(Entity entity, boolean glow, String glowColor) {
      entity.setGlowing(glow);
      if (this.plugin.platform().isFolia()) {
         return;
      }
      String teamName = glow && glowColor != null && !glowColor.isBlank()
         ? "npc_glow_" + glowColor.toLowerCase(Locale.ROOT).replaceAll("[<>]", "")
         : "npc_hidden";
      List<Scoreboard> boards = new ArrayList<>();
      boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getScoreboard() != Bukkit.getScoreboardManager().getMainScoreboard()) {
            boards.add(player.getScoreboard());
         }
      }
      for (Scoreboard scoreboard : boards) {
         Team team = scoreboard.getTeam(teamName);
         if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
            if (teamName.startsWith("npc_glow_")) {
               try {
                  team.color((NamedTextColor)NamedTextColor.NAMES.value(teamName.substring(9)));
               } catch (Exception ignored) {
               }
            }
         }
         team.addEntry(entity.getUniqueId().toString());
      }
   }

   private void syncFakePlayerGlowTeams(PacketFakePlayer fake, NpcDefinition def) {
      if (this.plugin.platform().isFolia()) {
         // Solid-color glow + nametag hide are applied per-viewer from PacketFakePlayer#showTo.
         return;
      }
      if (!def.glow || def.glowColor == null || def.glowColor.isBlank()) {
         return;
      }
      String cleanColor = def.glowColor.toLowerCase(Locale.ROOT).replaceAll("[<>]", "");
      if (!"rainbow".equals(cleanColor)) {
         // Packet bodies use per-viewer team packets; Bukkit teams do not recolor them.
         return;
      }

      Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
      List<Scoreboard> boards = new ArrayList<>();
      boards.add(mainBoard);
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getScoreboard() != mainBoard) {
            boards.add(player.getScoreboard());
         }
      }
      this.syncFakePlayerGlowTeamsOnBoards(fake, def, boards);
   }

   private void syncFakePlayerGlowTeamsOnBoards(PacketFakePlayer fake, NpcDefinition def, List<Scoreboard> boards) {
      for (Scoreboard board : boards) {
         for (Team existingTeam : board.getTeams()) {
            if (existingTeam.getName().startsWith("npc_")) {
               existingTeam.removeEntry(fake.getProfileName());
            }
         }
      }

      this.rainbowEntries.remove(fake.getProfileName());
      String cleanColor = def.glowColor == null ? "" : def.glowColor.toLowerCase(Locale.ROOT).replaceAll("[<>]", "");
      if (!def.glow || !"rainbow".equals(cleanColor)) {
         return;
      }

      String teamName = "npc_rainbow";

      for (Scoreboard board : boards) {
         Team team = board.getTeam(teamName);
         if (team == null) {
            team = board.registerNewTeam(teamName);
            team.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
            team.color(RAINBOW_COLORS[0]);
         }

         if (!team.hasEntry(fake.getProfileName())) {
            team.addEntry(fake.getProfileName());
         }
      }

      this.rainbowEntries.put(fake.getProfileName(), 0);
   }

   private void publishFakePlayerToNearbyViewers(PacketFakePlayer fake, Location location) {
      this.plugin.platform().runForNearbyPlayers(location, NpcDistanceIndex.VISIBILITY_RADIUS_SQ, fake::resync);
   }

   private Location locationFromAnchorKey(World world, long key) {
      int x = (int)(key >> 38 & 0x3FFFFFFL);
      int y = (int)(key >> 26 & 0xFFF);
      int z = (int)(key & 0x3FFFFFFL);
      if (x >= 1 << 25) {
         x -= 1 << 26;
      }
      if (z >= 1 << 25) {
         z -= 1 << 26;
      }
      return new Location(world, x, y, z);
   }

   private record StoredBlockProp(
      String id,
      Location blockAnchor,
      BlockData blockData,
      String propType,
      long anchorKey,
      boolean glow,
      String glowColor,
      boolean persistent
   ) {
   }

   public void apply(World world, List<? extends NpcDefinition> definitions) {
      this.apply(world, definitions, true);
   }

   /**
    * Reconciles NPC entities for {@code world} against {@code definitions}.
    *
    * <p>When {@code removeUnlisted} is {@code true} this is a full reconcile: entities whose id is not in
    * {@code definitions} are removed (used by command handlers that pass the complete desired set). When it is
    * {@code false} this is a partial upsert that only spawns/updates the listed NPCs and leaves all other NPC
    * entities untouched. The partial mode is required by {@link network.skypvp.paper.PaperCorePlugin#reloadNpcDecorations},
    * which applies one definition at a time on its own region thread — running a full reconcile per definition
    * made each NPC delete every other NPC on the same world (deletions there are handled separately by
    * {@link #removeMissingDefinitions(World, java.util.Set)}).
    */
   public void apply(World world, List<? extends NpcDefinition> definitions, boolean removeUnlisted) {
      String worldName = world.getName();
      if (removeUnlisted) {
         this.activeDefinitions.values().removeIf(d -> d.location != null && worldName.equals(d.location.world));
      }
      Map<String, NpcDefinition> desiredById = new HashMap<>();

      for (NpcDefinition def : definitions) {
         if (def != null && def.id != null && !def.id.isBlank()) {
            String normId = def.id.toLowerCase(Locale.ROOT);
            desiredById.put(normId, def);
            this.activeDefinitions.put(normId, def);
         }
      }

      List<String> deletedNpcIds = new ArrayList<>();
      Map<String, LivingEntity> existingById = new HashMap<>();
      Map<String, Interaction> existingInteractionsById = new HashMap<>();
      int orphanedCount = 0;

      for (Entity entity : world.getEntities()) {
         String npcId = this.readNpcId(entity);
         if (npcId != null && !npcId.isBlank()) {
            String key = npcId.toLowerCase(Locale.ROOT);
            NpcDefinition desired = desiredById.get(key);
            if (desired == null) {
               if (!removeUnlisted) {
                  // Partial apply: this entity belongs to a different NPC we are not reconciling now.
                  continue;
               }
               entity.remove();
               this.activeEntities.remove(key);
               this.activeInteractions.remove(key);
               deletedNpcIds.add(key);
               PacketFakePlayer fake = this.fakePlayers.remove(key);
               if (fake != null) {
                  for (Player p : Bukkit.getOnlinePlayers()) {
                     fake.destroy(p);
                  }
               }
               this.destroyMobNpc(key);
            } else {
               boolean typeMatches = false;
               String currentType = entity.getType().name();
               String desiredType = desired.entityType == null ? "VILLAGER" : desired.entityType.toUpperCase(Locale.ROOT);
               if ("INTERACTION".equals(currentType)) {
                  typeMatches = true;
               } else if ("PLAYER".equalsIgnoreCase(desiredType)) {
                  typeMatches = false;
               } else {
                  typeMatches = false;
               }

               if (!typeMatches) {
                  entity.remove();
                  this.activeEntities.remove(key);
                  this.activeInteractions.remove(key);
                  PacketFakePlayer fake = this.fakePlayers.remove(key);
                  if (fake != null) {
                     for (Player p : Bukkit.getOnlinePlayers()) {
                        fake.destroy(p);
                     }
                  }
                  this.destroyMobNpc(key);
               } else if (existingById.containsKey(key) || existingInteractionsById.containsKey(key)) {
                  entity.remove();
               } else if (entity instanceof LivingEntity le) {
                  existingById.put(key, le);
               } else if (entity instanceof Interaction ie) {
                  existingInteractionsById.put(key, ie);
               }
            }
         } else if (removeUnlisted && entity instanceof LivingEntity && this.isLikelyOrphanedNpc((LivingEntity)entity, desiredById)) {
            entity.remove();
            orphanedCount++;
         }
      }

      for (Entry<String, NpcDefinition> entry : desiredById.entrySet()) {
         String id = entry.getKey();
         NpcDefinition defx = entry.getValue();
         Location location = this.toLocation(world, defx.location);
         if ("PLAYER".equalsIgnoreCase(defx.entityType)) {
            Interaction existing = existingInteractionsById.get(id);
            if (existing != null && existing.isValid() && !existing.isDead()) {
               if (this.plugin.platform().isFolia()) {
                  existing.teleportAsync(location);
               } else {
                  existing.teleport(location);
               }
               PersistentDataContainer pdc = existing.getPersistentDataContainer();
               pdc.set(this.actionTypeKey, PersistentDataType.STRING, defx.actionType == null ? "NONE" : defx.actionType);
               pdc.set(this.actionDataKey, PersistentDataType.STRING, defx.actionData == null ? "" : defx.actionData);
               pdc.set(this.facePlayerKey, PersistentDataType.STRING, String.valueOf(defx.facePlayer));
            } else {
               this.cleanSpawnArea(location, 1.0);
               this.spawnNpc(world, defx, location);
            }

            PacketFakePlayer fake = this.fakePlayers.remove(id);
            if (fake != null) {
               for (Player p : Bukkit.getOnlinePlayers()) {
                  fake.destroy(p);
               }
            }

            String profileName = id.length() > 16 ? id.substring(0, 16) : id;
            PacketFakePlayer newFake = new PacketFakePlayer(this.plugin, profileName, defx.skinUrl, defx.skinSignature, location, defx.glow, defx.glowColor);
            this.fakePlayers.put(id, newFake);

            this.syncFakePlayerGlowTeams(newFake, defx);
            this.plugin.platform().runForNearbyPlayers(location, NpcDistanceIndex.VISIBILITY_RADIUS_SQ, newFake::resync);
         } else {
            Interaction existingHitbox = existingInteractionsById.get(id);
            if (existingHitbox != null && existingHitbox.isValid() && !existingHitbox.isDead()) {
               if (this.plugin.platform().isFolia()) {
                  existingHitbox.teleportAsync(location);
               } else {
                  existingHitbox.teleport(location);
               }
               this.writeInteractionMetadata(existingHitbox, defx);
            } else {
               this.cleanSpawnArea(location, 1.0);
               this.spawnInteractionHitbox(world, defx, location);
            }

            this.removeMobDisplayEntity(id);
            this.configureMobNpc(id, defx, location);
         }
      }

      List<HologramDefinition> holoDefs = new ArrayList<>();

      for (NpcDefinition defx : desiredById.values()) {
         if (defx.hologramLines != null && !defx.hologramLines.isEmpty()) {
            HologramDefinition holo = new HologramDefinition();
            holo.id = defx.id + "_holo";
            holo.parentId = defx.id;
            holo.anchor = defx.location;
            holo.offsetY = 2.2;
            holo.lines = new ArrayList<>(defx.hologramLines);
            holoDefs.add(holo);
         }
      }

      for (String deletedId : deletedNpcIds) {
         HologramDefinition holo = new HologramDefinition();
         holo.id = deletedId + "_holo";
         holo.parentId = deletedId;
         holo.lines = new ArrayList<>();
         holoDefs.add(holo);
      }

      this.plugin.holographicLibrary().apply(world, holoDefs);
      this.rebuildDistanceIndex(world);
      if (orphanedCount > 0) {
         this.plugin.getLogger().info("[NPC] Cleaned up " + orphanedCount + " orphaned entities in " + world.getName());
      }
   }

   private void rebuildDistanceIndex(World world) {
      Map<String, Entity> worldInteractions = new HashMap<>();
      Map<String, Entity> worldDisplays = new HashMap<>();
      for (Entry<String, Interaction> entry : this.activeInteractions.entrySet()) {
         Interaction interaction = entry.getValue();
         if (interaction != null && interaction.isValid() && !interaction.isDead() && world.equals(interaction.getWorld())) {
            worldInteractions.put(entry.getKey(), interaction);
         }
      }
      for (Entry<String, LivingEntity> entry : this.activeEntities.entrySet()) {
         LivingEntity entity = entry.getValue();
         if (entity != null && entity.isValid() && !entity.isDead() && world.equals(entity.getWorld())) {
            worldDisplays.put(entry.getKey(), entity);
         }
      }
      this.distanceIndex.rebuildWorld(world, worldInteractions, worldDisplays);
      for (NpcBlockPropHandle prop : this.activeBlockProps.values()) {
         if (prop.blockAnchor().getWorld() == null || !prop.blockAnchor().getWorld().equals(world)) {
            continue;
         }
         this.distanceIndex.registerBlockProp(prop.id(), prop.blockAnchor(), prop.display(), prop.interaction());
      }
   }

   private void destroyAllFakesFor(Player player) {
      if (player == null) {
         return;
      }
      for (PacketFakePlayer fake : this.fakePlayers.values()) {
         fake.destroy(player);
      }
      for (PacketFakeMob fake : this.fakeMobs.values()) {
         fake.destroy(player);
      }
   }

   private void configureMobNpc(String id, NpcDefinition def, Location location) {
      this.destroyMobNpc(id);

      EntityType type = this.entityTypeFor(def);
      float scale = (float) (def.scale <= 0.0 ? 1.0 : def.scale);
      PacketFakeMob mob = new PacketFakeMob(this.plugin, type, location, scale, def.glow, def.glowColor);
      this.fakeMobs.put(id, mob);

      World world = location.getWorld();
      if (world == null) {
         return;
      }
      this.plugin.platform().runForNearbyPlayers(location, NpcDistanceIndex.VISIBILITY_RADIUS_SQ, mob::resync);
      this.plugin.getLogger().info("[NPC] configureMobNpc id='" + id + "' type=" + type.name()
         + " world=" + world.getName() + " at " + location.getBlockX() + "," + location.getBlockY()
         + "," + location.getBlockZ() + " packet-mob per-viewer");
   }

   private void destroyMobNpc(String id) {
      PacketFakeMob fake = this.fakeMobs.remove(id);
      if (fake != null) {
         for (Player player : Bukkit.getOnlinePlayers()) {
            fake.destroy(player);
         }
      }
      this.removeMobDisplayEntity(id);
   }

   private void removeMobDisplayEntity(String id) {
      LivingEntity display = this.activeEntities.remove(id);
      if (display != null && display.isValid() && !display.isDead()) {
         display.remove();
      }
      Entity other = this.activeNonPlayerEntities.remove(id);
      if (other != null && other.isValid() && !other.isDead()) {
         other.remove();
      }
   }

   private EntityType entityTypeFor(NpcDefinition def) {
      if (def == null || def.entityType == null || def.entityType.isBlank()) {
         return EntityType.VILLAGER;
      }
      try {
         return EntityType.valueOf(def.entityType.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
         return EntityType.VILLAGER;
      }
   }

   private boolean isLikelyOrphanedNpc(LivingEntity entity, Map<String, NpcDefinition> desiredNpcs) {
      String type = entity.getType().name();
      return type.equals("VILLAGER") && entity.getCustomName() == null && !entity.hasAI() && entity.isInvulnerable()
         ? true
         : type.equals("ARMOR_STAND") && entity.isInvisible() && entity.isInvulnerable() && entity.getCustomName() == null;
   }

   private void cleanSpawnArea(Location loc, double radius) {
      World world = loc.getWorld();
      if (world != null) {
         for (Entity entity : world.getNearbyEntities(loc, radius, radius, radius)) {
            String npcId = this.readNpcId(entity);
            if (npcId == null || npcId.isBlank()) {
               if (entity instanceof Interaction) {
                  entity.remove();
               } else if (entity instanceof LivingEntity && !entity.getType().name().startsWith("PLAYER")) {
                  entity.remove();
               }
            }
         }
      }
   }

   public boolean handleInteract(Player player, Entity entity) {
      String npcId = this.readNpcId(entity);
      if (npcId != null && !npcId.isBlank()) {
         // A single right-click on an entity makes the client send both INTERACT and INTERACT_AT
         // packets, so the action would otherwise fire twice (double server-connect / double command).
         // Debounce per player on a short window and still consume the event to cancel vanilla behaviour.
         long now = System.currentTimeMillis();
         Long last = this.lastInteractMs.put(player.getUniqueId(), now);
         if (last != null && now - last < 250L) {
            return true;
         }
         String actionType = this.readPdc(entity, this.actionTypeKey);
         String actionData = this.readPdc(entity, this.actionDataKey);
         InteractionActionExecutor.execute(this.plugin, player, actionType, actionData);
         NetworkSoundCue.NPC_INTERACT.play(player);
         return true;
      } else {
         return false;
      }
   }

   private Entity spawnNpc(World world, NpcDefinition def, Location location) {
      if ("PLAYER".equalsIgnoreCase(def.entityType)) {
         Interaction hitbox = this.spawnInteractionHitbox(world, def, location);
         return hitbox;
      }
      this.spawnInteractionHitbox(world, def, location);
      return this.spawnDisplayEntity(world, def, location);
   }

   private Interaction spawnInteractionHitbox(World world, NpcDefinition def, Location location) {
      String id = def.id.toLowerCase(Locale.ROOT);
      Interaction hitbox = (Interaction)world.spawn(location, Interaction.class, interaction -> {
         interaction.setInteractionWidth(this.interactionWidth(def));
         interaction.setInteractionHeight(this.interactionHeight(def));
         interaction.setResponsive(true);
         interaction.setPersistent(false);
      });
      this.writeInteractionMetadata(hitbox, def);
      this.activeInteractions.put(id, hitbox);
      return hitbox;
   }

   private void writeInteractionMetadata(Interaction hitbox, NpcDefinition def) {
      PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
      pdc.set(this.npcIdKey, PersistentDataType.STRING, def.id);
      pdc.set(this.actionTypeKey, PersistentDataType.STRING, def.actionType == null ? "NONE" : def.actionType);
      pdc.set(this.actionDataKey, PersistentDataType.STRING, def.actionData == null ? "" : def.actionData);
      pdc.set(this.facePlayerKey, PersistentDataType.STRING, String.valueOf(def.facePlayer));
   }

   private float interactionWidth(NpcDefinition def) {
      String type = def.entityType == null ? "VILLAGER" : def.entityType.toUpperCase(Locale.ROOT);
      return switch (type) {
         case "CHICKEN", "BEE", "PARROT", "BAT" -> 0.6F;
         case "VILLAGER", "PLAYER" -> 0.8F;
         default -> 0.9F;
      };
   }

   private float interactionHeight(NpcDefinition def) {
      String type = def.entityType == null ? "VILLAGER" : def.entityType.toUpperCase(Locale.ROOT);
      return switch (type) {
         case "CHICKEN", "BEE", "PARROT", "BAT" -> 1.0F;
         case "VILLAGER" -> 1.9F;
         default -> 1.4F;
      };
   }

   private Entity spawnDisplayEntity(World world, NpcDefinition def, Location location) {
      EntityType type;
      try {
         type = EntityType.valueOf(def.entityType == null ? "VILLAGER" : def.entityType.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
         type = EntityType.VILLAGER;
      }

      Entity spawned = world.spawnEntity(location, type);
      if (spawned instanceof LivingEntity living) {
         living.setPersistent(false);
         return living;
      }
      spawned.remove();
      return null;
   }

   private void applyMetadata(Entity entity, NpcDefinition def, List<Scoreboard> boards) {
      if (entity instanceof LivingEntity le) {
         le.setAI(false);
         le.setCollidable(false);
         le.setCanPickupItems(false);
      } else if (entity instanceof Interaction interaction) {
         interaction.setResponsive(true);
      }

      entity.setInvulnerable(true);
      entity.setSilent(true);
      entity.setGravity(false);
      entity.setCustomNameVisible(false);
      entity.customName(null);
      if (!"PLAYER".equalsIgnoreCase(def.entityType)) {
         entity.setGlowing(def.glow);
         entity.setInvisible(false);
      } else {
         entity.setInvisible(true);
      }

      String teamName;
      boolean isRainbow = false;
      if (!this.plugin.platform().isFolia()) {
         if (def.glow && def.glowColor != null && !def.glowColor.isBlank()) {
            String cleanColor = def.glowColor.toLowerCase(Locale.ROOT).replaceAll("[<>]", "");
            if ("rainbow".equals(cleanColor)) {
               teamName = "npc_rainbow";
               isRainbow = true;
            } else {
               teamName = "npc_glow_" + cleanColor;
            }
         } else {
            teamName = "npc_hidden";
         }

         for (Scoreboard sb : boards) {
            Team team = sb.getTeam(teamName);
            if (team == null) {
               team = sb.registerNewTeam(teamName);
               team.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
               if (isRainbow) {
                  team.color(RAINBOW_COLORS[0]);
               } else if (teamName.startsWith("npc_glow_")) {
                  try {
                     String colorName = teamName.substring(9);
                     team.color((NamedTextColor)NamedTextColor.NAMES.value(colorName));
                  } catch (Exception var15) {
                  }
               }
            }

            String entryString = entity.getUniqueId().toString();
            team.addEntry(entryString);
            if (isRainbow) {
               this.rainbowEntries.put(entryString, 0);
            }

            if ("PLAYER".equalsIgnoreCase(def.entityType)) {
               String id = this.readNpcId(entity);
               if (id != null) {
                  String profileName = id.length() > 16 ? id.substring(0, 16) : id;
                  team.addEntry(profileName);
               }
            }
         }
      }

      PersistentDataContainer pdc = entity.getPersistentDataContainer();

      if (entity instanceof ArmorStand stand && "PLAYER".equalsIgnoreCase(def.entityType) && def.skinUrl != null && !def.skinUrl.isBlank()) {
         ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
         SkullMeta meta = (SkullMeta)skull.getItemMeta();
         PlayerProfile profile = this.plugin.getServer().createProfile(UUID.randomUUID(), def.id);

         try {
            profile.setProperty(new ProfileProperty("textures", def.skinUrl));
            meta.setPlayerProfile(profile);
         } catch (Exception var14) {
         }

         skull.setItemMeta(meta);
         EntityEquipment eq = stand.getEquipment();
         if (eq != null) {
            eq.setHelmet(skull);
            ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
            ItemStack legs = new ItemStack(Material.LEATHER_LEGGINGS);
            ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
            LeatherArmorMeta lam = (LeatherArmorMeta)chest.getItemMeta();
            lam.setColor(Color.fromRGB(43240));
            chest.setItemMeta(lam);
            legs.setItemMeta(lam);
            boots.setItemMeta(lam);
            eq.setChestplate(chest);
            eq.setLeggings(legs);
            eq.setBoots(boots);
         }
      }

      pdc.set(this.npcIdKey, PersistentDataType.STRING, def.id);
      pdc.set(this.actionTypeKey, PersistentDataType.STRING, def.actionType == null ? "NONE" : def.actionType);
      pdc.set(this.actionDataKey, PersistentDataType.STRING, def.actionData == null ? "" : def.actionData);
      pdc.set(this.facePlayerKey, PersistentDataType.STRING, String.valueOf(def.facePlayer));

      double npcScale = def.scale <= 0.0 ? 1.0 : def.scale;
      if (npcScale != 1.0) {
         try {
            org.bukkit.attribute.Attribute scaleAttr = org.bukkit.attribute.Attribute.valueOf("GENERIC_SCALE");
            org.bukkit.attribute.AttributeInstance attr = ((LivingEntity)entity).getAttribute(scaleAttr);
            if (attr != null) {
               attr.setBaseValue(npcScale);
            }
         } catch (Throwable ignored) {
         }
      }
   }

   private String readNpcId(Entity entity) {
      return entity == null ? null : this.readPdc(entity, this.npcIdKey);
   }

   private String readPdc(Entity entity, NamespacedKey key) {
      return (String)entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
   }

   private Location toLocation(World fallbackWorld, WorldPoint point) {
      if (point == null) {
         return fallbackWorld.getSpawnLocation();
      } else {
         World world = this.plugin.getServer().getWorld(point.world);
         if (world == null) {
            world = fallbackWorld;
         }

         return new Location(world, point.x, point.y, point.z, point.yaw, point.pitch);
      }
   }

   public Location resolveLocation(World fallbackWorld, NpcDefinition definition) {
      return definition == null ? fallbackWorld.getSpawnLocation() : this.toLocation(fallbackWorld, definition.location);
   }

   /**
    * Folia-safe reconcile for {@code /npc} command handlers. Entity spawn/teleport/remove must run on the
    * region that owns {@code anchor} (mirrors {@link network.skypvp.paper.PaperCorePlugin#reloadNpcDecorations});
    * running {@link #apply} on the global region thread throws "Accessing entity state off owning region's
    * thread" on Folia, which previously aborted the handler before the NPC was spawned and before the player
    * feedback was sent. {@code feedback} always runs on that region thread afterwards, even if the reconcile
    * fails, so command confirmation messages are never silently lost.
    */
   public void applyAndNotify(World world, Location anchor, List<? extends NpcDefinition> definitions, Runnable feedback) {
      if (world == null) {
         if (feedback != null) {
            this.plugin.platform().runGlobal(feedback);
         }
         return;
      }
      Location target = anchor != null ? anchor : world.getSpawnLocation();
      this.plugin.platform().runAtLocation(target, () -> {
         try {
            this.apply(world, definitions);
         } catch (Throwable error) {
            this.plugin.getLogger().warning("[NPC] reconcile failed during command: " + error);
         }
         if (feedback != null) {
            try {
               feedback.run();
            } catch (Throwable ignored) {
            }
         }
      });
   }

   public void removeMissingDefinitions(World world, Set<String> desiredIds) {
      if (world == null) {
         return;
      }
      String worldName = world.getName();
      Set<String> normalizedDesired = desiredIds == null
         ? Set.of()
         : desiredIds.stream().filter(id -> id != null && !id.isBlank()).map(id -> id.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
      List<String> deletedNpcIds = new ArrayList<>();

      for (String id : new ArrayList<>(this.activeDefinitions.keySet())) {
         NpcDefinition active = this.activeDefinitions.get(id);
         if (active == null || active.location == null || !worldName.equals(active.location.world)) {
            continue;
         }
         if (normalizedDesired.contains(id)) {
            continue;
         }

         this.activeDefinitions.remove(id);
         deletedNpcIds.add(id);
         Interaction interaction = this.activeInteractions.remove(id);
         if (interaction != null && interaction.isValid() && !interaction.isDead()) {
            interaction.remove();
         }
         this.activeEntities.remove(id);
         PacketFakePlayer fakePlayer = this.fakePlayers.remove(id);
         if (fakePlayer != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
               fakePlayer.destroy(player);
            }
         }
         this.destroyMobNpc(id);
      }

      if (!deletedNpcIds.isEmpty()) {
         List<HologramDefinition> holoDefs = new ArrayList<>();
         for (String deletedId : deletedNpcIds) {
            HologramDefinition holo = new HologramDefinition();
            holo.id = deletedId + "_holo";
            holo.parentId = deletedId;
            holo.lines = new ArrayList<>();
            holoDefs.add(holo);
         }
         this.plugin.holographicLibrary().apply(world, holoDefs);
      }

      this.rebuildDistanceIndex(world);
   }
}
