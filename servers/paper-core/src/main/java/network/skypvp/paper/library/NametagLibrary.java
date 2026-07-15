package network.skypvp.paper.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatPlaceholderBridge;
import network.skypvp.paper.library.packet.PacketGlowTeams;
import network.skypvp.paper.model.NametagDefinition;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Core system for the custom nametag rendered above every player's head.
 *
 * <p>Each configured line spawns a {@link TextDisplay} (or {@link ItemDisplay} for {@code <item:...>} lines)
 * that rides the player as a passenger, so the stack follows the player smoothly client-side. Lines are
 * MiniMessage templates with PlaceholderAPI support, built-in placeholders (health, food, level, world, ping),
 * hologram-style {@code <anim:...>} animations and a {@code <glow:color>} outline tag. The vanilla nametag is
 * hidden through the same per-viewer packet teams the NPC bodies use, so it never doubles up with the custom
 * stack. The layout is edited in-game via {@code /nametag}, persisted to PostgreSQL, cached locally, and
 * reloaded network-wide through the decoration refresh channel.
 */
public final class NametagLibrary implements Listener {

   private static final Pattern GRADIENT = Pattern.compile("<gradient:([^>]+)>");
   private static final int TEXT_DISPLAY_LINE_WIDTH = 4096;

   private final PaperCorePlugin plugin;
   private final NamespacedKey nametagOwnerKey;
   private final Map<UUID, List<MountedLine>> mounted = new ConcurrentHashMap<>();
   /** Game-mode supplied suppression rules (e.g. extraction hides tags while a player is inside a breach). */
   private final Map<String, Predicate<Player>> hideConditions = new ConcurrentHashMap<>();
   /** Players explicitly hidden via {@link #setHidden(Player, boolean)}. */
   private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();

   private volatile NametagDefinition definition = new NametagDefinition();
   private volatile List<ParsedLine> parsedLines = List.of();
   private PlatformTask refreshTask;
   private int refreshPeriodTicks = -1;
   private long globalTick = 0L;

   public NametagLibrary(PaperCorePlugin plugin) {
      this.plugin = plugin;
      this.nametagOwnerKey = new NamespacedKey(plugin, "core_nametag_owner");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   // ------------------------------------------------------------------
   // Public API
   // ------------------------------------------------------------------

   /** Snapshot of the currently applied layout. */
   public NametagDefinition definitionSnapshot() {
      return this.definition.copy();
   }

   /** Applies a layout in-memory (persistence is handled by {@link network.skypvp.paper.repository.NametagRepository}). */
   public void applyDefinition(NametagDefinition newDefinition) {
      this.applyDefinitionInternal(newDefinition == null ? new NametagDefinition() : newDefinition);
   }

   /**
    * Registers a suppression rule: while {@code condition} matches a player, their nametag stack is unmounted.
    * The rule is re-evaluated on every refresh tick, so callers do not need to hook enter/leave transitions.
    * Game modes use this — e.g. extraction hides tags while the player is inside a breach.
    */
   public void registerHideCondition(String id, Predicate<Player> condition) {
      if (id == null || id.isBlank() || condition == null) {
         return;
      }
      this.hideConditions.put(id.toLowerCase(Locale.ROOT), condition);
   }

   public void unregisterHideCondition(String id) {
      if (id != null) {
         this.hideConditions.remove(id.toLowerCase(Locale.ROOT));
      }
   }

   /** Explicitly hides or shows one player's nametag, independent of any registered hide conditions. */
   public void setHidden(Player player, boolean hidden) {
      if (player == null) {
         return;
      }
      if (hidden) {
         this.hiddenPlayers.add(player.getUniqueId());
         this.plugin.platform().runOnPlayer(player, () -> this.unmount(player));
      } else {
         this.hiddenPlayers.remove(player.getUniqueId());
         this.plugin.platform().runOnPlayer(player, () -> this.remount(player));
      }
   }

   public boolean isHidden(Player player) {
      if (player == null) {
         return true;
      }
      if (this.hiddenPlayers.contains(player.getUniqueId())) {
         return true;
      }
      for (Predicate<Player> condition : this.hideConditions.values()) {
         try {
            if (condition.test(player)) {
               return true;
            }
         } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("[Nametag] Hide condition failed: " + exception.getMessage());
         }
      }
      return false;
   }

   /**
    * Re-applies the per-viewer hide-vanilla-name teams for a viewer whose client scoreboard was just replaced
    * (switching scoreboards wipes packet teams client-side). Mirrors {@code NpcLibrary.resyncViewer}.
    *
    * <p>Also required after {@code hidePlayer}/{@code showPlayer}: the client drops team membership when an
    * entity is despawned/respawned, but the server packet-team cache still thinks hide is applied and skips
    * re-send — which surfaces the vanilla nametag (notably after death/rejoin in extraction).
    */
   public void resyncViewer(Player viewer) {
      if (viewer == null || !viewer.isOnline() || !this.shouldHideVanillaNames()) {
         return;
      }
      for (Player target : this.plugin.getServer().getOnlinePlayers()) {
         if (target == null || !target.isOnline()) {
            continue;
         }
         PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, target.getName());
         PacketGlowTeams.applyPacketEntityTeam(this.plugin, viewer, target.getName(), false, null, true);
      }
   }

   /**
    * Forces every online viewer to re-hide {@code target}'s vanilla nametag. Use after the target was
    * re-shown via {@code showPlayer} (reconnect, spectator exit, tab-visibility reconcile).
    */
   public void resyncTarget(Player target) {
      if (target == null || !target.isOnline() || !this.shouldHideVanillaNames()) {
         return;
      }
      String name = target.getName();
      for (Player viewer : this.plugin.getServer().getOnlinePlayers()) {
         if (viewer == null || !viewer.isOnline()) {
            continue;
         }
         PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, name);
         PacketGlowTeams.applyPacketEntityTeam(this.plugin, viewer, name, false, null, true);
      }
   }

   public void shutdown() {
      if (this.refreshTask != null) {
         this.refreshTask.cancel();
         this.refreshTask = null;
      }
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.plugin.platform().runOnPlayer(player, () -> this.unmount(player));
      }
      this.clearVanillaNameHiding();
   }

   // ------------------------------------------------------------------
   // Definition lifecycle
   // ------------------------------------------------------------------

   private void applyDefinitionInternal(NametagDefinition newDefinition) {
      this.definition = newDefinition;
      List<ParsedLine> parsed = new ArrayList<>();
      if (newDefinition.lines != null) {
         for (String raw : newDefinition.lines) {
            parsed.add(new ParsedLine(raw));
         }
      }
      this.parsedLines = List.copyOf(parsed);
      this.restartRefreshTask();
      this.syncVanillaNameHiding();
      this.plugin.platform().runForEachPlayer(this::remount);
   }

   private void restartRefreshTask() {
      int period = Math.max(1, this.definition.refreshTicks);
      if (this.hasAnimations()) {
         period = Math.min(4, period);
      }
      if (this.refreshTask != null && period == this.refreshPeriodTicks) {
         return;
      }
      if (this.refreshTask != null) {
         this.refreshTask.cancel();
         this.refreshTask = null;
      }
      this.refreshPeriodTicks = period;
      if (!this.isActive()) {
         return;
      }
      int capturedPeriod = period;
      this.refreshTask = this.plugin.platformScheduler().runGlobalTimer(() -> {
         this.globalTick += capturedPeriod;
         long tick = this.globalTick;
         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            this.plugin.platform().runOnPlayer(player, () -> this.refreshPlayer(player, tick));
         }
      }, period, period);
   }

   private boolean hasAnimations() {
      for (ParsedLine line : this.parsedLines) {
         if (line.animation != TextAnimation.NONE) {
            return true;
         }
      }
      return false;
   }

   private boolean isActive() {
      return this.definition.enabled && !this.parsedLines.isEmpty() && this.matchesServerScope();
   }

   /** True when the layout's scopes include this server ({@code global} or the server's decoration scope). */
   private boolean matchesServerScope() {
      List<String> scopes = this.definition.scopes;
      if (scopes == null || scopes.isEmpty()) {
         return true;
      }
      String serverScope = this.plugin.decorationScope().toLowerCase(Locale.ROOT);
      for (String scope : scopes) {
         if (scope == null) {
            continue;
         }
         String normalized = scope.toLowerCase(Locale.ROOT).trim();
         if (normalized.equals("global") || normalized.equals(serverScope)) {
            return true;
         }
      }
      return false;
   }

   private boolean shouldHideVanillaNames() {
      return this.isActive() && this.definition.hideVanillaName;
   }

   // ------------------------------------------------------------------
   // Mounting
   // ------------------------------------------------------------------

   private void remount(Player player) {
      this.unmount(player);
      this.mount(player);
   }

   private void mount(Player player) {
      if (player == null || !player.isOnline() || player.isDead() || !this.isActive() || this.isHidden(player)) {
         return;
      }
      if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
         return;
      }
      NametagDefinition def = this.definition;
      List<ParsedLine> lines = this.parsedLines;
      List<MountedLine> mountedLines = new ArrayList<>(lines.size());
      int count = lines.size();
      for (int index = 0; index < count; index++) {
         ParsedLine line = lines.get(index);
         if (line.isEmpty) {
            mountedLines.add(new MountedLine(null, index));
            continue;
         }
         double offsetY = def.baseHeight + (double) (count - 1 - index) * def.lineSpacing * def.scale;
         Entity display;
         try {
            display = line.itemMaterial != null
               ? this.spawnItemLine(player, line, def, offsetY)
               : this.spawnTextLine(player, line, def, offsetY);
         } catch (RuntimeException exception) {
            this.plugin.getLogger().warning("[Nametag] Failed to spawn line for " + player.getName() + ": " + exception.getMessage());
            continue;
         }
         if (!player.addPassenger(display)) {
            display.remove();
            continue;
         }
         if (!def.visibleToSelf) {
            player.hideEntity(this.plugin, display);
         }
         mountedLines.add(new MountedLine(display.getUniqueId(), index));
      }
      this.mounted.put(player.getUniqueId(), mountedLines);
   }

   private TextDisplay spawnTextLine(Player player, ParsedLine line, NametagDefinition def, double offsetY) {
      return player.getWorld().spawn(player.getLocation(), TextDisplay.class, display -> {
         display.setBillboard(Billboard.CENTER);
         display.setPersistent(false);
         display.setSeeThrough(true);
         display.setShadowed(false);
         display.setGravity(false);
         display.setInvulnerable(true);
         display.setLineWidth(TEXT_DISPLAY_LINE_WIDTH);
         display.setDefaultBackground(false);
         display.setBackgroundColor(def.background ? Color.fromARGB(64, 0, 0, 0) : Color.fromARGB(0, 0, 0, 0));
         display.setTransformation(this.lineTransformation(def.scale, offsetY));
         this.applyGlowOutline(display, line);
         display.getPersistentDataContainer().set(this.nametagOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
         display.text(this.buildLineComponent(player, line, this.globalTick));
      });
   }

   private ItemDisplay spawnItemLine(Player player, ParsedLine line, NametagDefinition def, double offsetY) {
      return player.getWorld().spawn(player.getLocation(), ItemDisplay.class, display -> {
         display.setItemStack(new ItemStack(line.itemMaterial));
         display.setItemDisplayTransform(ItemDisplayTransform.FIXED);
         display.setBillboard(Billboard.CENTER);
         display.setPersistent(false);
         display.setGravity(false);
         display.setInvulnerable(true);
         display.setTransformation(this.lineTransformation(line.itemScale * def.scale, offsetY));
         this.applyGlowOutline(display, line);
         display.getPersistentDataContainer().set(this.nametagOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
      });
   }

   private void applyGlowOutline(Entity display, ParsedLine line) {
      if (line.glowColor == null) {
         return;
      }
      display.setGlowing(true);
      NamedTextColor color = PacketGlowTeams.resolveColor(true, line.glowColor);
      ((org.bukkit.entity.Display) display).setGlowColorOverride(Color.fromRGB(color.value()));
   }

   private Transformation lineTransformation(float scale, double offsetY) {
      float safeScale = scale <= 0.0F ? 1.0F : scale;
      return new Transformation(
         new Vector3f(0.0F, (float) offsetY, 0.0F),
         new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
         new Vector3f(safeScale, safeScale, safeScale),
         new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)
      );
   }

   private void unmount(Player player) {
      List<MountedLine> lines = this.mounted.remove(player.getUniqueId());
      if (lines == null) {
         return;
      }
      for (MountedLine line : lines) {
         if (line.entityId == null) {
            continue;
         }
         Entity entity = this.plugin.getServer().getEntity(line.entityId);
         if (entity != null) {
            this.plugin.platform().runAtEntity(entity, entity::remove);
         }
      }
   }

   // ------------------------------------------------------------------
   // Refreshing
   // ------------------------------------------------------------------

   private void refreshPlayer(Player player, long tick) {
      if (!player.isOnline() || player.isDead()) {
         return;
      }
      if (!this.isActive() || this.isHidden(player)) {
         this.unmount(player);
         return;
      }
      List<MountedLine> lines = this.mounted.get(player.getUniqueId());
      if (lines == null || !this.isMountIntact(player, lines)) {
         this.remount(player);
         lines = this.mounted.get(player.getUniqueId());
         if (lines == null) {
            return;
         }
      }
      List<ParsedLine> parsed = this.parsedLines;
      for (MountedLine line : lines) {
         if (line.entityId == null || line.lineIndex >= parsed.size()) {
            continue;
         }
         ParsedLine parsedLine = parsed.get(line.lineIndex);
         if (parsedLine.itemMaterial != null) {
            continue;
         }
         Entity entity = this.plugin.getServer().getEntity(line.entityId);
         if (entity instanceof TextDisplay textDisplay && textDisplay.isValid()) {
            textDisplay.text(this.buildLineComponent(player, parsedLine, tick));
         }
      }
   }

   private boolean isMountIntact(Player player, List<MountedLine> lines) {
      for (MountedLine line : lines) {
         if (line.entityId == null) {
            continue;
         }
         Entity entity = this.plugin.getServer().getEntity(line.entityId);
         if (entity == null || !entity.isValid() || entity.isDead() || !player.equals(entity.getVehicle())) {
            return false;
         }
      }
      return true;
   }

   // ------------------------------------------------------------------
   // Text rendering
   // ------------------------------------------------------------------

   private Component buildLineComponent(Player player, ParsedLine line, long tick) {
      String text = this.applyBuiltinPlaceholders(player, line.baseText);
      text = ChatPlaceholderBridge.apply(player, text);
      text = this.applyTextAnimation(line.animation, text, tick);
      try {
         return ServerTextUtil.miniMessageComponent(text);
      } catch (Exception exception) {
         return ServerTextUtil.component(stripTags(text));
      }
   }

   private String applyBuiltinPlaceholders(Player player, String text) {
      if (text.indexOf('%') < 0) {
         return text;
      }
      return text
         .replace("%player_name%", player.getName())
         .replace("%health%", String.valueOf((int) Math.ceil(player.getHealth())))
         .replace("%max_health%", String.valueOf((int) Math.ceil(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
               ? 20.0
               : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())))
         .replace("%food%", String.valueOf(player.getFoodLevel()))
         .replace("%level%", String.valueOf(player.getLevel()))
         .replace("%world%", player.getWorld().getName())
         .replace("%ping%", String.valueOf(player.getPing()));
   }

   private String applyTextAnimation(TextAnimation animation, String text, long tick) {
      switch (animation) {
         case GLOW:
            return injectGradientPhase(text, tick * 50L);
         case RAINBOW:
            return "<rainbow:" + tick % 100L + ">" + stripTags(text) + "</rainbow>";
         case BLINK:
            return tick / 10L % 2L == 0L ? "" : text;
         case SCROLL: {
            String leadingTags = extractLeadingTags(text);
            String plain = stripTags(text);
            if (plain.isEmpty()) {
               return text;
            }
            int offset = (int) (tick / 5L % (long) Math.max(1, plain.length() + 10));
            if (offset < plain.length()) {
               return leadingTags + plain.substring(offset) + "   " + plain.substring(0, offset);
            }
            return leadingTags + plain;
         }
         case TYPEWRITER: {
            int plainLength = visibleLength(text);
            if (plainLength <= 0) {
               return text;
            }
            int length = (int) (tick / 3L % (long) Math.max(1, plainLength + 20));
            return length < plainLength ? substringPreservingTags(text, length) + "_" : text;
         }
         case NONE:
         default:
            return text;
      }
   }

   /** Same technique as the animated scoreboard titles: shift every gradient's phase over time. */
   private static String injectGradientPhase(String input, long tickMillis) {
      double phase = Math.sin(tickMillis / 500.0);
      String phaseString = String.format(Locale.ROOT, "%.3f", phase);
      Matcher matcher = GRADIENT.matcher(input);
      StringBuilder out = new StringBuilder();
      while (matcher.find()) {
         String inner = matcher.group(1);
         matcher.appendReplacement(out, Matcher.quoteReplacement("<gradient:" + inner + ":" + phaseString + ">"));
      }
      matcher.appendTail(out);
      return out.toString();
   }

   private static String stripTags(String text) {
      return text == null ? "" : text.replaceAll("<[^>]+>", "");
   }

   private static String extractLeadingTags(String text) {
      StringBuilder tags = new StringBuilder();
      boolean inTag = false;
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
            tags.append(c);
         } else if (c == '>') {
            inTag = false;
            tags.append(c);
         } else if (inTag) {
            tags.append(c);
         } else {
            break;
         }
      }
      return tags.toString();
   }

   private static int visibleLength(String text) {
      int count = 0;
      boolean inTag = false;
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
         } else if (c == '>') {
            inTag = false;
         } else if (!inTag) {
            count++;
         }
      }
      return count;
   }

   private static String substringPreservingTags(String text, int length) {
      StringBuilder result = new StringBuilder();
      int visibleCount = 0;
      boolean inTag = false;
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '<') {
            inTag = true;
         }
         result.append(c);
         if (c == '>') {
            inTag = false;
         } else if (!inTag && ++visibleCount >= length) {
            break;
         }
      }
      return result.toString();
   }

   // ------------------------------------------------------------------
   // Vanilla name hiding
   // ------------------------------------------------------------------

   private void syncVanillaNameHiding() {
      if (this.shouldHideVanillaNames()) {
         for (Player viewer : this.plugin.getServer().getOnlinePlayers()) {
            this.applyVanillaHideFor(viewer);
         }
      } else {
         this.clearVanillaNameHiding();
      }
   }

   private void applyVanillaHideFor(Player viewer) {
      for (Player target : this.plugin.getServer().getOnlinePlayers()) {
         PacketGlowTeams.applyPacketEntityTeam(this.plugin, viewer, target.getName(), false, null, true);
      }
   }

   private void clearVanillaNameHiding() {
      for (Player viewer : this.plugin.getServer().getOnlinePlayers()) {
         for (Player target : this.plugin.getServer().getOnlinePlayers()) {
            PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, target.getName());
         }
      }
   }

   // ------------------------------------------------------------------
   // Events
   // ------------------------------------------------------------------

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (this.shouldHideVanillaNames()) {
         // Force remove+apply: a prior session may have left a matching cache signature, which would
         // skip the hide packet while the new client entity has no team membership yet.
         this.resyncViewer(player);
         this.resyncTarget(player);
      }
      this.plugin.platform().runOnPlayerLater(player, () -> {
         if (this.shouldHideVanillaNames()) {
            this.resyncViewer(player);
            this.resyncTarget(player);
         }
         this.remount(player);
      }, 5L);
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      Player leaving = event.getPlayer();
      this.unmount(leaving);
      this.hiddenPlayers.remove(leaving.getUniqueId());
      // Client drops team membership on disconnect; clear server cache so the next join re-sends hide.
      if (this.shouldHideVanillaNames()) {
         String name = leaving.getName();
         for (Player viewer : this.plugin.getServer().getOnlinePlayers()) {
            if (viewer == null || viewer.getUniqueId().equals(leaving.getUniqueId())) {
               continue;
            }
            PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, name);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      // Passengers are dismounted on death; remove them so they do not linger at the death spot.
      this.unmount(event.getEntity());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerRespawn(PlayerRespawnEvent event) {
      Player player = event.getPlayer();
      this.plugin.platform().runOnPlayerLater(player, () -> {
         if (this.shouldHideVanillaNames()) {
            // Respawn recreates the client player entity; re-bind hide teams for this target.
            this.resyncTarget(player);
            this.resyncViewer(player);
         }
         this.remount(player);
      }, 2L);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerTeleport(PlayerTeleportEvent event) {
      // Teleports strip passengers; the displays stay behind at the old spot until we remount.
      Player player = event.getPlayer();
      this.plugin.platform().runOnPlayerLater(player, () -> this.remount(player), 2L);
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
      Player player = event.getPlayer();
      this.plugin.platform().runOnPlayerLater(player, () -> this.remount(player), 2L);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onGameModeChange(PlayerGameModeChangeEvent event) {
      Player player = event.getPlayer();
      this.plugin.platform().runOnPlayerLater(player, () -> this.remount(player), 1L);
   }

   // ------------------------------------------------------------------
   // Line model
   // ------------------------------------------------------------------

   private record MountedLine(UUID entityId, int lineIndex) {
   }

   private enum TextAnimation {
      NONE,
      GLOW,
      RAINBOW,
      BLINK,
      SCROLL,
      TYPEWRITER
   }

   private static final class ParsedLine {
      final TextAnimation animation;
      final String baseText;
      final Material itemMaterial;
      final float itemScale;
      final String glowColor;
      final boolean isEmpty;

      ParsedLine(String rawText) {
         String text = rawText == null ? "" : rawText;
         text = ServerTextUtil.stripAnimationMarkup(text);
         TextAnimation parsedAnimation = TextAnimation.NONE;
         if (rawText != null && rawText.contains("<anim:glow>")) {
            parsedAnimation = TextAnimation.GLOW;
         } else if (rawText != null && rawText.contains("<anim:rainbow>")) {
            parsedAnimation = TextAnimation.RAINBOW;
         } else if (rawText != null && rawText.contains("<anim:blink>")) {
            parsedAnimation = TextAnimation.BLINK;
         } else if (rawText != null && rawText.contains("<anim:scroll>")) {
            parsedAnimation = TextAnimation.SCROLL;
         } else if (rawText != null && rawText.contains("<anim:typewriter>")) {
            parsedAnimation = TextAnimation.TYPEWRITER;
         }
         this.animation = parsedAnimation;

         String parsedGlowColor = null;
         Matcher glowMatcher = Pattern.compile("<glow:([a-zA-Z_]+)>").matcher(text);
         if (glowMatcher.find()) {
            parsedGlowColor = glowMatcher.group(1);
            text = glowMatcher.replaceAll("");
         }
         this.glowColor = parsedGlowColor;

         Material parsedItem = null;
         float parsedItemScale = 0.5F;
         if (text.startsWith("<item:") && text.contains(">")) {
            int end = text.indexOf('>');
            String[] parts = text.substring(6, end).split(":");
            try {
               parsedItem = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
            if (parts.length > 1) {
               try {
                  parsedItemScale = Float.parseFloat(parts[1]);
               } catch (NumberFormatException ignored) {
               }
            }
         }
         this.itemMaterial = parsedItem;
         this.itemScale = parsedItemScale <= 0.0F ? 0.5F : parsedItemScale;

         this.baseText = network.skypvp.shared.ServerTextUtil.applySmallCapsTags(text);
         String plain = stripTags(text).trim();
         this.isEmpty = parsedItem == null && (plain.isEmpty() || text.equalsIgnoreCase("<empty>"));
      }
   }
}
