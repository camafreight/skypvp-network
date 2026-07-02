package network.skypvp.paper.service;

import network.skypvp.shared.ServerTextUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import network.skypvp.paper.library.packet.PacketSidebar;
import network.skypvp.shared.RankRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class ScoreboardService {
   private static final long TICK_MILLIS = 50L;
   private static final String[] ENTRIES = new String[]{"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"};
   private static final int LINE_COUNT = 15;
   private static final String OBJ_NAME = "skypvp_sidebar";
   private final PaperCorePlugin plugin;
   private final RankService rankService;

   private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
   private final Map<UUID, PacketSidebar> packetBoards = new ConcurrentHashMap<>();
   private final Map<UUID, ScoreboardService.ScoreboardSnapshot> lastSnapshots = new ConcurrentHashMap<>();
   private final Map<UUID, Long> pendingSetupAtMillis = new ConcurrentHashMap<>();

   public ScoreboardService(PaperCorePlugin plugin, RankService rankService) {
      this.plugin = plugin;
      this.rankService = rankService;
   }



   public void setupPlayer(Player player) {
      this.runScoreboardSafe(player, () -> this.setupPlayerInternal(player));
   }

   private void setupPlayerInternal(Player player) {
      if (this.plugin.gameModeBehaviorService().booleanValue("core.hud.scoreboard.enabled", true)) {
         ScoreboardService.ScoreboardSnapshot snapshot = this.snapshot(player);
         if (snapshot.lines().isEmpty()) {
            this.teardownPlayerInternal(player);
            return;
         }
         if (this.plugin.platform().isFolia()) {
            this.updatePacketSidebar(player, snapshot);
            return;
         }
         if (!this.boards.containsKey(player.getUniqueId())) {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = board.registerNewObjective("skypvp_sidebar", Criteria.DUMMY, snapshot.title());
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            // Hide the red side numbers entirely (Paper blank number format) instead of relying on
            // blank color-code entries, which still render a number column on vanilla clients.
            try {
               obj.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            } catch (Throwable ignored) {
            }

            for (int i = 0; i < 15; i++) {
               Team team = board.registerNewTeam("skypvp_" + i);
               team.addEntry(ENTRIES[i]);
               team.prefix(Component.text(""));
               team.suffix(Component.text(""));
               Score score = obj.getScore(ENTRIES[i]);
               score.setScore(15 - i);
            }

            this.updateBoard(player, board, snapshot);
            this.boards.put(player.getUniqueId(), board);
            this.pendingSetupAtMillis.remove(player.getUniqueId());
            player.setScoreboard(board);
            this.plugin.npcLibrary().resyncViewer(player);
            if (this.plugin.nametagLibrary() != null) {
               this.plugin.nametagLibrary().resyncViewer(player);
            }
         }
      }
   }

   public void queueSetup(Player player) {
      int delayTicks = 5;
      this.pendingSetupAtMillis.put(player.getUniqueId(), System.currentTimeMillis() + (long)delayTicks * 50L);
   }

   public void teardownPlayer(Player player) {
      this.runScoreboardSafe(player, () -> this.teardownPlayerInternal(player));
   }

   private void teardownPlayerInternal(Player player) {
      this.boards.remove(player.getUniqueId());
      this.lastSnapshots.remove(player.getUniqueId());
      this.pendingSetupAtMillis.remove(player.getUniqueId());
      PacketSidebar packetSidebar = this.packetBoards.remove(player.getUniqueId());
      if (packetSidebar != null) {
         packetSidebar.remove(player);
      }
      if (this.plugin.platform().isFolia()) {
         return;
      }
      player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
   }

   public void refresh() {
      if (this.plugin.hudProviderService().activeProvider().isEmpty()) {
         return;
      }
      if (this.plugin.gameModeBehaviorService().booleanValue("core.hud.scoreboard.enabled", true)) {
         this.plugin.platformScheduler().runForEachPlayerOnGlobal(this::refreshPlayer);
      } else {
         this.plugin.platformScheduler().runForEachPlayerOnGlobal(this::teardownPlayer);
      }
   }

   public void refreshPlayer(Player player) {
      if (player == null || !player.isOnline()) {
         return;
      }
      ScoreboardService.ScoreboardSnapshot snapshot = this.snapshot(player);
      if (snapshot.lines().isEmpty()) {
         this.teardownPlayerInternal(player);
         return;
      }
      if (this.plugin.platform().isFolia()) {
         this.updatePacketSidebar(player, snapshot);
         return;
      }
      Scoreboard board = this.boards.get(player.getUniqueId());
      if (board == null) {
         if (this.isPendingSetup(player.getUniqueId()) && !this.isSetupDue(player.getUniqueId())) {
            return;
         }

         this.setupPlayerInternal(player);
         board = this.boards.get(player.getUniqueId());
      }

      if (board != null) {
         this.updateBoard(player, board, snapshot);
      }
   }

   private void updatePacketSidebar(Player player, ScoreboardService.ScoreboardSnapshot snapshot) {
      if (!PacketEventsBridge.isAvailable()) {
         return;
      }
      boolean firstCreate = !this.packetBoards.containsKey(player.getUniqueId());
      PacketSidebar sidebar = this.packetBoards.computeIfAbsent(player.getUniqueId(), id -> new PacketSidebar(this.plugin));
      sidebar.update(player, snapshot.title(), snapshot.lines());
      this.lastSnapshots.put(player.getUniqueId(), snapshot);
      if (firstCreate) {
         this.plugin.platform().runOnPlayerLater(player, () -> {
            this.plugin.npcLibrary().refreshViewerPacketGlow(player);
            if (this.plugin.nametagLibrary() != null) {
               this.plugin.nametagLibrary().resyncViewer(player);
            }
         }, 2L);
      }
   }

   private void runScoreboardSafe(Player player, Runnable task) {
      if (this.plugin.platform().isFolia()) {
         this.plugin.platformScheduler().runAtLocation(player.getLocation(), task);
      } else {
         task.run();
      }
   }

   public ScoreboardService.ScoreboardSnapshot snapshot(Player player) {
      if (!this.plugin.gameModeBehaviorService().booleanValue("core.hud.scoreboard.enabled", true)) {
         return new ScoreboardService.ScoreboardSnapshot("disabled", Component.text(""), List.of(), false, false);
      } else {
         RankRecord rank = this.rankService != null ? this.rankService.getCached(player.getUniqueId()) : RankRecord.DEFAULT;
         Optional<HudProvider.ScoreboardFrame> resolvedFrame = this.plugin
            .hudProviderService()
            .activeProvider()
            .flatMap(
               activeProvider -> this.resolveProviderFrame(activeProvider, player, rank, System.currentTimeMillis())
            );
         Component title = resolvedFrame.map(HudProvider.ScoreboardFrame::title).orElse(Component.text(""));
         List<Component> lines = resolvedFrame.map(HudProvider.ScoreboardFrame::lines).orElse(List.of());
         return new ScoreboardService.ScoreboardSnapshot("provider", title, lines, true, resolvedFrame.isPresent());
      }
   }

   private void updateBoard(Player player, Scoreboard board, ScoreboardService.ScoreboardSnapshot snapshot) {
      if (snapshot.enabled()) {
         if (snapshot.lines().isEmpty()) {
            this.teardownPlayerInternal(player);
            return;
         }
         Objective obj = board.getObjective("skypvp_sidebar");
         if (obj != null) {
            ScoreboardService.ScoreboardSnapshot previous = this.lastSnapshots.get(player.getUniqueId());
            if (previous == null || !previous.title().equals(snapshot.title())) {
               obj.displayName(snapshot.title());
            }

            for (int i = 0; i < 15; i++) {
               Team team = board.getTeam("skypvp_" + i);
               if (team != null) {
                  Component nextLine = this.lineAt(snapshot.lines(), i);
                  Component previousLine = (Component)(previous == null ? Component.text("") : this.lineAt(previous.lines(), i));
                  if (!previousLine.equals(nextLine)) {
                     team.prefix(nextLine);
                  }
               }
            }

            this.lastSnapshots.put(player.getUniqueId(), snapshot);
         }
      }
   }

   private Optional<HudProvider.ScoreboardFrame> resolveProviderFrame(
      HudProvider provider, Player player, RankRecord rank, long tick
   ) {
      try {
         HudProvider.Context context = this.plugin.hudProviderService().createContext(player, rank, Map.of(), tick);
         return provider.scoreboard(new HudProvider.ScoreboardContext(context, Component.text(""), List.of()));
      } catch (Exception var10) {
         return Optional.empty();
      }
   }

   private boolean isPendingSetup(UUID playerId) {
      return this.pendingSetupAtMillis.containsKey(playerId);
   }

   private boolean isSetupDue(UUID playerId) {
      return System.currentTimeMillis() >= this.pendingSetupAtMillis.getOrDefault(playerId, 0L);
   }

   private Component lineAt(List<Component> lines, int index) {
      return (Component)(index < lines.size() ? lines.get(index) : Component.text(""));
   }

   public static record ScoreboardSnapshot(String templateId, Component title, List<Component> lines, boolean enabled, boolean providerApplied) {
   }
}
