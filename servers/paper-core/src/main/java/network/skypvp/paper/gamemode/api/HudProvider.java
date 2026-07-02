package network.skypvp.paper.gamemode.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Flag;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;

public interface HudProvider {
   String modeKey();

   default Optional<Component> actionBar(HudProvider.ActionBarContext context) {
      return Optional.empty();
   }

   default Optional<HudProvider.TabFrame> tabList(HudProvider.TabListContext context) {
      return Optional.empty();
   }

   default Optional<HudProvider.ScoreboardFrame> scoreboard(HudProvider.ScoreboardContext context) {
      return Optional.empty();
   }

   default Optional<HudProvider.BossBarFrame> bossBar(HudProvider.BossBarContext context) {
      return Optional.empty();
   }

   public static record ActionBarContext(HudProvider.Context base, Component defaultContent) {
      public ActionBarContext(HudProvider.Context base, Component defaultContent) {
         Objects.requireNonNull(base, "base");
         Component var3 = (Component)(defaultContent == null ? Component.text("") : defaultContent);
         this.base = base;
         this.defaultContent = var3;
      }
   }

   public static record BossBarContext(
      HudProvider.Context base,
      Component defaultTitle,
      float defaultProgress,
      Color defaultColor,
      Overlay defaultOverlay,
      Set<Flag> defaultFlags,
      boolean defaultVisible
   ) {
      public BossBarContext(
         HudProvider.Context base,
         Component defaultTitle,
         float defaultProgress,
         Color defaultColor,
         Overlay defaultOverlay,
         Set<Flag> defaultFlags,
         boolean defaultVisible
      ) {
         Objects.requireNonNull(base, "base");
         Component var8 = (Component)(defaultTitle == null ? Component.text("") : defaultTitle);
         defaultProgress = Math.max(0.0F, Math.min(1.0F, defaultProgress));
         defaultColor = defaultColor == null ? Color.BLUE : defaultColor;
         defaultOverlay = defaultOverlay == null ? Overlay.PROGRESS : defaultOverlay;
         defaultFlags = Set.copyOf(defaultFlags == null ? Set.of() : defaultFlags);
         this.base = base;
         this.defaultTitle = var8;
         this.defaultProgress = defaultProgress;
         this.defaultColor = defaultColor;
         this.defaultOverlay = defaultOverlay;
         this.defaultFlags = defaultFlags;
         this.defaultVisible = defaultVisible;
      }
   }

   public static record BossBarFrame(Component title, float progress, Color color, Overlay overlay, Set<Flag> flags, boolean visible) {
      public BossBarFrame(Component title, float progress, Color color, Overlay overlay, Set<Flag> flags, boolean visible) {
         Component var7 = (Component)(title == null ? Component.text("") : title);
         progress = Math.max(0.0F, Math.min(1.0F, progress));
         color = color == null ? Color.BLUE : color;
         overlay = overlay == null ? Overlay.PROGRESS : overlay;
         flags = Set.copyOf(flags == null ? Set.of() : flags);
         this.title = var7;
         this.progress = progress;
         this.color = color;
         this.overlay = overlay;
         this.flags = flags;
         this.visible = visible;
      }

      public BossBarFrame(Component title, float progress, Color color, Overlay overlay) {
         this(title, progress, color, overlay, Set.of(), true);
      }
   }

   public static record Context(
      Player player,
      String serverId,
      String serverRole,
      int onlinePlayers,
      int maxPlayers,
      RankRecord rank,
      Map<String, String> placeholders,
      long tickMillis,
      String clientLocale
   ) {
      public Context(
         Player player,
         String serverId,
         String serverRole,
         int onlinePlayers,
         int maxPlayers,
         RankRecord rank,
         Map<String, String> placeholders,
         long tickMillis,
         String clientLocale
      ) {
         player = Objects.requireNonNull(player, "player");
         serverId = serverId == null ? "" : serverId;
         serverRole = serverRole == null ? "" : serverRole;
         rank = rank == null ? RankRecord.DEFAULT : rank;
         placeholders = Map.copyOf(placeholders == null ? Map.of() : placeholders);
         clientLocale = clientLocale == null || clientLocale.isBlank()
                 ? network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale()
                 : network.skypvp.shared.chat.ClientLocaleUtil.normalizeMinecraftLocale(clientLocale);
         this.player = player;
         this.serverId = serverId;
         this.serverRole = serverRole;
         this.onlinePlayers = onlinePlayers;
         this.maxPlayers = maxPlayers;
         this.rank = rank;
         this.placeholders = placeholders;
         this.tickMillis = tickMillis;
         this.clientLocale = clientLocale;
      }

      /** Backward-compatible constructor without explicit locale (uses player locale when available). */
      public Context(
         Player player,
         String serverId,
         String serverRole,
         int onlinePlayers,
         int maxPlayers,
         RankRecord rank,
         Map<String, String> placeholders,
         long tickMillis
      ) {
         this(
                 player,
                 serverId,
                 serverRole,
                 onlinePlayers,
                 maxPlayers,
                 rank,
                 placeholders,
                 tickMillis,
                 player == null ? network.skypvp.shared.chat.ClientLocaleUtil.defaultMinecraftLocale() : player.getLocale()
         );
      }

      public UUID playerId() {
         return this.player.getUniqueId();
      }

      public String playerName() {
         return this.player.getName();
      }

      public int playerPing() {
         return this.player.getPing();
      }

      public String placeholder(String key) {
         return this.placeholders.getOrDefault(key, "");
      }
   }

   public static record ScoreboardContext(HudProvider.Context base, Component defaultTitle, List<Component> defaultLines) {
      public ScoreboardContext(HudProvider.Context base, Component defaultTitle, List<Component> defaultLines) {
         Objects.requireNonNull(base, "base");
         Component var4 = (Component)(defaultTitle == null ? Component.text("") : defaultTitle);
         defaultLines = List.copyOf(defaultLines == null ? List.of() : defaultLines);
         this.base = base;
         this.defaultTitle = var4;
         this.defaultLines = defaultLines;
      }
   }

   public static record ScoreboardFrame(Component title, List<Component> lines) {
      public ScoreboardFrame(Component title, List<Component> lines) {
         Component var3 = (Component)(title == null ? Component.text("") : title);
         lines = List.copyOf(lines == null ? List.of() : lines);
         this.title = var3;
         this.lines = lines;
      }
   }

   public static record TabFrame(Component header, Component footer, Component playerListName) {
      public TabFrame(Component header, Component footer, Component playerListName) {
         Component var4 = (Component)(header == null ? Component.text("") : header);
         Component var5 = (Component)(footer == null ? Component.text("") : footer);
         Component var6 = (Component)(playerListName == null ? Component.text("") : playerListName);
         this.header = var4;
         this.footer = var5;
         this.playerListName = var6;
      }

      public TabFrame(Component header, Component footer) {
         this(header, footer, Component.text(""));
      }
   }

   public static record TabListContext(HudProvider.Context base, Component defaultHeader, Component defaultFooter, Component defaultPlayerListName) {
      public TabListContext(HudProvider.Context base, Component defaultHeader, Component defaultFooter, Component defaultPlayerListName) {
         Objects.requireNonNull(base, "base");
         Component var5 = (Component)(defaultHeader == null ? Component.text("") : defaultHeader);
         Component var6 = (Component)(defaultFooter == null ? Component.text("") : defaultFooter);
         Component var7 = (Component)(defaultPlayerListName == null ? Component.text("") : defaultPlayerListName);
         this.base = base;
         this.defaultHeader = var5;
         this.defaultFooter = var6;
         this.defaultPlayerListName = var7;
      }
   }
}
