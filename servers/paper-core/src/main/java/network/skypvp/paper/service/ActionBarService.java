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
   private final Set<UUID> suppressed = Collections.newSetFromMap(new ConcurrentHashMap<>());
   private final ConcurrentHashMap<UUID, ActionBarService.OverrideFrame> overrides = new ConcurrentHashMap<>();
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

   public void showTemporary(UUID playerId, Component content, int durationTicks) {
      if (playerId != null && content != null) {
         long now = System.currentTimeMillis();
         long expiresAt = now + (long)Math.max(1, durationTicks) * 50L;
         this.overrides.put(playerId, new ActionBarService.OverrideFrame(content, expiresAt));
      }
   }

   public void showTemporary(UUID playerId, Component content) {
      this.showTemporary(playerId, content, 40);
   }

   public void refresh() {
      if (this.plugin.hudProviderService().activeProvider().isEmpty()) {
         return;
      }
      this.plugin.platformScheduler().runForEachPlayer(this::refreshPlayer);
   }

   public void refreshPlayer(Player player) {
      ActionBarService.ActionBarSnapshot snapshot = this.snapshot(player);
      if (snapshot.enabled() && !snapshot.suppressed() && !snapshot.content().equals(Component.text(""))) {
         player.sendActionBar(snapshot.content());
      }
   }

   public ActionBarService.ActionBarSnapshot snapshot(Player player) {
      boolean enabled = this.plugin.gameModeBehaviorService().booleanValue("core.hud.action-bar.enabled", true);
      boolean suppressedPlayer = this.suppressed.contains(player.getUniqueId());
      if (enabled && !suppressedPlayer) {
         ActionBarService.OverrideFrame override = this.overrides.get(player.getUniqueId());
         if (override != null) {
            if (!override.isExpired()) {
               return new ActionBarService.ActionBarSnapshot(override.content(), true, false, true);
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
         return new ActionBarService.ActionBarSnapshot(providerBar.orElse(defaultBar), true, false, providerBar.isPresent());
      } else {
         return new ActionBarService.ActionBarSnapshot(Component.text(""), enabled, suppressedPlayer, false);
      }
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

   public static record ActionBarSnapshot(Component content, boolean enabled, boolean suppressed, boolean providerApplied) {
   }

   private static record OverrideFrame(Component content, long expiresAt) {
      boolean isExpired() {
         return System.currentTimeMillis() >= this.expiresAt;
      }
   }
}
