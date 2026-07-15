package network.skypvp.paper.service;

import network.skypvp.shared.ServerTextUtil;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Flag;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;

public final class BossBarService {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final Overlay OVERLAY = Overlay.PROGRESS;
   private static final Set<Flag> DEFAULT_FLAGS = Set.of();
   private final PaperCorePlugin plugin;
   private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
   private final Map<UUID, AppliedFrame> lastApplied = new ConcurrentHashMap<>();

   public BossBarService(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public void showForPlayer(Player player) {
      BossBarService.BossBarSnapshot snapshot = this.snapshot(player);
      if (snapshot.enabled() && snapshot.visible()) {
         this.hideForPlayer(player);
         BossBar bar = this.createBar(snapshot);
         this.bars.put(player.getUniqueId(), bar);
         this.lastApplied.put(player.getUniqueId(), AppliedFrame.from(snapshot));
         player.showBossBar(bar);
      } else {
         this.hideForPlayer(player);
      }
   }

   public void hideForPlayer(Player player) {
      BossBar bar = this.bars.remove(player.getUniqueId());
      this.lastApplied.remove(player.getUniqueId());
      if (bar != null) {
         player.hideBossBar(bar);
      }
   }

   public void refresh() {
      if (this.plugin.hudProviderService().activeProvider().isEmpty()) {
         return;
      }
      this.plugin.platformScheduler().runForEachPlayer(this::refreshPlayer);
   }

   public void refreshPlayer(Player player) {
      var pipeline = this.plugin.clientUpdatePipeline();
      if (pipeline != null) {
         pipeline.offerBossBarRefresh(player);
         return;
      }
      this.flushPlayer(player);
   }

   /** Pipeline drain entry — applies the current boss-bar snapshot with content diff. */
   public void flushPlayer(Player player) {
      BossBarService.BossBarSnapshot snapshot = this.snapshot(player);
      if (snapshot.enabled() && snapshot.visible()) {
         BossBar bar = this.bars.get(player.getUniqueId());
         AppliedFrame next = AppliedFrame.from(snapshot);
         if (bar == null) {
            bar = this.createBar(snapshot);
            this.bars.put(player.getUniqueId(), bar);
            this.lastApplied.put(player.getUniqueId(), next);
            player.showBossBar(bar);
         } else if (!next.equals(this.lastApplied.get(player.getUniqueId()))) {
            this.applyFrame(bar, snapshot);
            this.lastApplied.put(player.getUniqueId(), next);
         }
      } else {
         this.hideForPlayer(player);
      }
   }

   public BossBarService.BossBarSnapshot snapshot(Player player) {
      boolean enabled = this.plugin.gameModeBehaviorService().booleanValue("core.hud.boss-bar.enabled", true);
      if (!enabled) {
         return new BossBarService.BossBarSnapshot(Component.text(""), 0.0F, Color.BLUE, OVERLAY, Set.of(), false, false, false);
      } else {
         RankRecord rank = this.plugin.rankService() != null ? this.plugin.rankService().getCached(player.getUniqueId()) : RankRecord.DEFAULT;
         Map<String, String> placeholders = java.util.Map.of();
         long tick = System.currentTimeMillis();
         
         Optional<HudProvider.BossBarFrame> resolvedFrame = this.plugin
            .hudProviderService()
            .activeProvider()
            .flatMap(activeProvider -> this.resolveProviderFrame(activeProvider, player, rank, placeholders, tick));
         
         if (resolvedFrame.isPresent()) {
            HudProvider.BossBarFrame frame = resolvedFrame.get();
            return new BossBarService.BossBarSnapshot(
               frame.title(), frame.progress(), frame.color(), frame.overlay(), frame.flags(), frame.visible(), true, true
            );
         } else {
            return new BossBarService.BossBarSnapshot(Component.text(""), 0.0F, Color.BLUE, OVERLAY, Set.of(), false, true, false);
         }
      }
   }

   private Optional<HudProvider.BossBarFrame> resolveProviderFrame(
      HudProvider provider, Player player, RankRecord rank, Map<String, String> placeholders, long tick
   ) {
      try {
         HudProvider.Context context = this.plugin.hudProviderService().createContext(player, rank, placeholders, tick);
         return provider.bossBar(
            new HudProvider.BossBarContext(
               context,
               Component.text(""),
               1.0F,
               Color.BLUE,
               OVERLAY,
               DEFAULT_FLAGS,
               true
            )
         );
      } catch (Exception var8) {
         return Optional.empty();
      }
   }

   private BossBar createBar(BossBarService.BossBarSnapshot snapshot) {
      BossBar bar = BossBar.bossBar(snapshot.title(), snapshot.progress(), snapshot.color(), snapshot.overlay());
      this.applyFlags(bar, snapshot.flags());
      return bar;
   }

   private void applyFrame(BossBar bar, BossBarService.BossBarSnapshot snapshot) {
      bar.name(snapshot.title());
      bar.progress(snapshot.progress());
      bar.color(snapshot.color());
      bar.overlay(snapshot.overlay());
      this.applyFlags(bar, snapshot.flags());
   }

   private void applyFlags(BossBar bar, Set<Flag> flags) {
      for (Flag flag : Flag.values()) {
         if (flags.contains(flag)) {
            bar.addFlag(flag);
         } else {
            bar.removeFlag(flag);
         }
      }
   }

   private void hideAll() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.hideForPlayer(player);
      }
   }

   public static record BossBarSnapshot(
      Component title, float progress, Color color, Overlay overlay, Set<Flag> flags, boolean visible, boolean enabled, boolean providerApplied
   ) {
   }

   /**
    * Diff key for client packets — avoids re-mutating Adventure boss bars when nothing changed.
    * Progress is quantized to ~0.5% so tiny float noise does not spam updates.
    */
   private static record AppliedFrame(Component title, int progressMillis, Color color, Overlay overlay, Set<Flag> flags) {
      static AppliedFrame from(BossBarSnapshot snapshot) {
         int quantized = Math.round(Math.max(0.0F, Math.min(1.0F, snapshot.progress())) * 200.0F);
         return new AppliedFrame(
               snapshot.title(),
               quantized,
               snapshot.color(),
               snapshot.overlay(),
               snapshot.flags() == null ? Set.of() : Set.copyOf(snapshot.flags())
         );
      }
   }
}
