package network.skypvp.paper.service;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.RankRecord;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.entity.Player;

public final class ActionBarService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final String BODY_HEX = ServerTextUtil.ThemeTone.BRAND_100.hex();
   private static final String HIGHLIGHT_HEX = ServerTextUtil.ThemeTone.BRAND_400.hex();
   private static final String STRUCTURE_HEX = ServerTextUtil.ThemeTone.BRAND_600.hex();
   public static final int REFRESH_TICKS = 40;
   /**
    * Resend the action bar at least this often even when the content is unchanged. The vanilla action bar fades out
    * after ~3s, so a heartbeat well under that keeps it solid while letting us skip the redundant packets that a
    * static bar would otherwise emit every refresh tick.
    */
   private static final long HEARTBEAT_MILLIS = 1000L;
   private final Set<UUID> suppressed = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final ConcurrentHashMap<UUID, ActionBarService.OverrideFrame> overrides = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, ActionBarService.SentFrame> lastSent = new ConcurrentHashMap<>();
   private final PaperCorePlugin plugin;
   private final RankService rankService;

   public ActionBarService(PaperCorePlugin plugin, RankService rankService) {
      this.plugin = plugin;
      this.rankService = rankService;
   }

   public void suppress(UUID playerId) {
      this.suppressed.add(playerId);
   }

   public void unsuppress(UUID playerId) {
      this.suppressed.remove(playerId);
   }

   /** Clears any sticky / temporary override so the next refresh can show the mode HUD again. */
   public void clearOverride(UUID playerId) {
      if (playerId != null) {
         this.overrides.remove(playerId);
         this.lastSent.remove(playerId);
      }
   }

   public void showTemporary(UUID playerId, Component content, int durationTicks) {
      if (playerId != null && content != null) {
         long now = System.currentTimeMillis();
         long expiresAt = now + (long)Math.max(1, durationTicks) * 50L;
         this.overrides.put(playerId, new ActionBarService.OverrideFrame(content, expiresAt));
      }
   }

   /**
    * Sticky override that also emits immediately when content changed (or heartbeat elapsed).
    * Prefer this over raw {@code player.sendActionBar} from gameplay code.
    */
   public void pushOverride(Player player, Component content, int durationTicks) {
      if (player == null || content == null) {
         return;
      }
      this.showTemporary(player.getUniqueId(), content, durationTicks);
      this.enqueueSend(player, content, network.skypvp.paper.clientupdate.ClientUpdatePipeline.PRIORITY_OVERRIDE);
   }

   public void showTemporary(UUID playerId, Component content) {
      this.showTemporary(playerId, content, 40);
   }

   public void refresh() {
      // No provider gate: even without a mode HUD the refresh keeps the persistent
      // level-badge overlay (see snapshot) from fading off the action bar.
      this.pruneOfflineSentFrames();
      this.plugin.platformScheduler().runForEachPlayer(this::refreshPlayer);
   }

   /**
    * Sends the player's action bar, but only when the rendered content actually changed since the last send or the
    * heartbeat window has elapsed. This keeps the bar visible while avoiding a packet on every refresh tick when the
    * content is identical (the common case for a static HUD).
    */
   public void refreshPlayer(Player player) {
      ActionBarService.ActionBarSnapshot snapshot = this.snapshot(player);
      UUID playerId = player.getUniqueId();
      if (!snapshot.enabled() || snapshot.suppressed() || snapshot.content().equals(Component.text(""))) {
         this.lastSent.remove(playerId);
         return;
      }
      int priority = snapshot.override()
            ? network.skypvp.paper.clientupdate.ClientUpdatePipeline.PRIORITY_OVERRIDE
            : network.skypvp.paper.clientupdate.ClientUpdatePipeline.PRIORITY_NORMAL;
      this.enqueueSend(player, snapshot.content(), priority);
   }

   /** Immediate emit used by {@link network.skypvp.paper.clientupdate.ClientUpdatePipeline} drain. */
   public void emitNow(Player player, Component content) {
      this.sendDeduped(player, content);
   }

   private void enqueueSend(Player player, Component content, int priority) {
      var pipeline = this.plugin.clientUpdatePipeline();
      if (pipeline != null) {
         pipeline.offerActionBar(player, content, priority);
         return;
      }
      this.sendDeduped(player, content);
   }

   private void sendDeduped(Player player, Component content) {
      UUID playerId = player.getUniqueId();
      long now = System.currentTimeMillis();
      ActionBarService.SentFrame previous = this.lastSent.get(playerId);
      boolean changed = previous == null || !previous.content().equals(content);
      boolean heartbeat = previous == null || now - previous.sentAt() >= HEARTBEAT_MILLIS;
      if (changed || heartbeat) {
         player.sendActionBar(content);
         this.lastSent.put(playerId, new ActionBarService.SentFrame(content, now));
      }
   }

   private void pruneOfflineSentFrames() {
      if (this.lastSent.isEmpty()) {
         return;
      }
      this.lastSent.keySet().removeIf(id -> {
         Player online = this.plugin.getServer().getPlayer(id);
         return online == null || !online.isOnline();
      });
   }

   public ActionBarService.ActionBarSnapshot snapshot(Player player) {
      boolean enabled = this.plugin.gameModeBehaviorService().booleanValue("core.hud.action-bar.enabled", true);
      boolean suppressedPlayer = this.suppressed.contains(player.getUniqueId());
      if (enabled && !suppressedPlayer) {
         ActionBarService.OverrideFrame override = this.overrides.get(player.getUniqueId());
         if (override != null) {
            if (!override.isExpired()) {
               return new ActionBarService.ActionBarSnapshot(override.content(), true, false, true, true);
            }

            this.overrides.remove(player.getUniqueId(), override);
         }

         long tick = System.currentTimeMillis();
         int online = this.plugin.getServer().getOnlinePlayers().size();
         RankRecord rank = this.rankService.getCached(player.getUniqueId());
         Map<String, String> placeholders = java.util.Map.of();
         Component defaultBar = Component.text("");
         Optional<Component> providerBar = this.plugin
            .hudProviderService()
            .activeProvider()
            .flatMap(activeProvider -> this.resolveProviderBar(activeProvider, player, rank, placeholders, tick, defaultBar));
         // The level-badge cluster is advance-neutral (net width 0), so appending it never
         // shifts the provider bar's centering. Overrides (transient plain text) skip it:
         // arbitrary text has nonzero width, which would push the badge off-center.
         Component content = providerBar.orElse(defaultBar);
         Component overlay = this.levelOverlay(player);
         if (overlay != null) {
            content = Component.text().append(content).append(overlay).build();
         }
         return new ActionBarService.ActionBarSnapshot(content, true, false, providerBar.isPresent(), false);
      } else {
         return new ActionBarService.ActionBarSnapshot(Component.text(""), enabled, suppressedPlayer, false, false);
      }
   }

   /** Persistent level-badge glyph cluster, or null when the level service is unavailable. */
   private Component levelOverlay(Player player) {
      network.skypvp.paper.service.PlayerLevelService levels = this.plugin.playerLevelService();
      return levels == null ? null : levels.hudOverlay(player);
   }

   private Optional<Component> resolveProviderBar(
      HudProvider provider, Player player, RankRecord rank, Map<String, String> placeholders, long tick, Component defaultBar
   ) {
      try {
         HudProvider.Context context = this.plugin.hudProviderService().createContext(player, rank, placeholders, tick);
         return provider.actionBar(new HudProvider.ActionBarContext(context, defaultBar));
      } catch (Exception var9) {
         return Optional.empty();
      }
   }

   public static record ActionBarSnapshot(Component content, boolean enabled, boolean suppressed, boolean providerApplied, boolean override) {
      public ActionBarSnapshot(Component content, boolean enabled, boolean suppressed, boolean providerApplied) {
         this(content, enabled, suppressed, providerApplied, false);
      }
   }

   private static record SentFrame(Component content, long sentAt) {
   }

   private static record OverrideFrame(Component content, long expiresAt) {
      boolean isExpired() {
         return System.currentTimeMillis() >= this.expiresAt;
      }
   }
}
