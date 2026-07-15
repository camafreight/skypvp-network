package network.skypvp.paper.service;

import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.tabboard.TabBoardService;
import network.skypvp.paper.tabboard.TabBoardSpec;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;

/** Tab list HUD: tab-board canvas is the single source of truth when a provider supplies a spec. */
public final class TabListService {

   /**
    * Consecutive failed board builds tolerated before the board actually releases the tab.
    * Death ticks, world transfers, and breach session teardown produce 1-2 cycle gaps where
    * the provider throws or returns empty; releasing immediately made the tab flip-flop
    * between the board canvas and the classic header/footer list.
    */
   private static final int BOARD_RELEASE_AFTER_FAILURES = 3;

   private final PaperCorePlugin plugin;
   private final RankService rankService;
   private final TabBoardService tabBoardService;
   private final Map<java.util.UUID, Integer> boardFailStreak = new java.util.concurrent.ConcurrentHashMap<>();

   public TabListService(PaperCorePlugin plugin, RankService rankService, TabBoardService tabBoardService) {
      this.plugin = plugin;
      this.rankService = rankService;
      this.tabBoardService = tabBoardService;
   }

   public void refresh() {
      this.plugin.platformScheduler().runForEachPlayerOnGlobal(this::refreshPlayer);
   }

   public void refreshPlayer(Player player) {
      var pipeline = this.plugin.clientUpdatePipeline();
      if (pipeline != null) {
         pipeline.offerTabRefresh(player);
         return;
      }
      this.flushPlayer(player);
   }

   /** Pipeline drain entry. */
   public void flushPlayer(Player player) {
      BoardOutcome outcome = applyTabBoard(player);
      if (outcome == BoardOutcome.OWNED) {
         // The board owns the whole tab: fake cells render the grid, the spec supplies
         // header/footer, and the real (hidden) entry contributes nothing.
         player.playerListName(Component.empty());
         return;
      }
      if (outcome == BoardOutcome.GRACE) {
         // Transient gap (death/world transfer/session teardown): keep the last board
         // frame on screen instead of bouncing to the classic tab and back.
         return;
      }
      TabListService.TabListSnapshot snapshot = this.snapshot(player);
      if (snapshot.headerFooterEnabled()) {
         player.sendPlayerListHeaderAndFooter(snapshot.header(), snapshot.footer());
      } else {
         player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
      }
      if (snapshot.playerListNameEnabled()) {
         player.playerListName(snapshot.playerListName());
      } else {
         player.playerListName(Component.empty());
      }
   }

   private enum BoardOutcome {
      OWNED,
      GRACE,
      RELEASED
   }

   private BoardOutcome applyTabBoard(Player player) {
      if (this.tabBoardService == null || !TabBoardService.isOperational()) {
         // Without the packet layer, no fake rows can render — claiming the tab here would
         // blank the vanilla list and leave the player with an empty tab.
         return BoardOutcome.RELEASED;
      }
      java.util.UUID viewerId = player.getUniqueId();
      Optional<HudProvider> provider = this.plugin.hudProviderService().activeProvider();
      if (provider.isEmpty()) {
         return releaseBoard(player, viewerId);
      }
      try {
         HudProvider.Context context = this.plugin.hudProviderService().createContext(
                 player,
                 this.rankService.getCached(player.getUniqueId()),
                 Map.of(),
                 System.currentTimeMillis()
         );
         Optional<TabBoardSpec> spec = provider.get().tabBoard(new HudProvider.TabListContext(
                 context,
                 Component.empty(),
                 Component.empty(),
                 Component.empty()
         ));
         if (spec.isPresent() && !spec.get().entries().isEmpty()) {
            this.tabBoardService.apply(player, spec.get());
            player.sendPlayerListHeaderAndFooter(spec.get().header(), spec.get().footer());
            boardFailStreak.remove(viewerId);
            return BoardOutcome.OWNED;
         }
      } catch (Exception ignored) {
         // fall through to the failure-streak decision below
      }
      return releaseBoard(player, viewerId);
   }

   /** Applies the failure-streak hysteresis before actually clearing the board. */
   private BoardOutcome releaseBoard(Player player, java.util.UUID viewerId) {
      if (!this.tabBoardService.isBoardActive(viewerId)) {
         boardFailStreak.remove(viewerId);
         return BoardOutcome.RELEASED;
      }
      int failures = boardFailStreak.merge(viewerId, 1, Integer::sum);
      if (failures < BOARD_RELEASE_AFTER_FAILURES) {
         return BoardOutcome.GRACE;
      }
      boardFailStreak.remove(viewerId);
      this.tabBoardService.clear(player);
      return BoardOutcome.RELEASED;
   }

   public TabListService.TabListSnapshot snapshot(Player player) {
      Optional<HudProvider> provider = this.plugin.hudProviderService().activeProvider();
      RankRecord rank = this.rankService.getCached(player.getUniqueId());
      Component defaultPlayerListName = resolveDefaultPlayerListName(player);

      Optional<HudProvider.TabFrame> resolvedFrame = provider.flatMap(
         activeProvider -> this.resolveProviderTabFrame(activeProvider, player, rank, defaultPlayerListName)
      );

      Component header = resolvedFrame.map(HudProvider.TabFrame::header).orElse(Component.text(""));
      Component footer = resolvedFrame.map(HudProvider.TabFrame::footer).orElse(Component.text(""));
      Component playerListName = resolvedFrame
         .map(HudProvider.TabFrame::playerListName)
         .filter(name -> !name.equals(Component.text("")))
         .orElse(defaultPlayerListName);

      boolean headerFooterEnabled = this.plugin.gameModeBehaviorService().booleanValue(
         CoreBehaviorKeys.TAB_HEADER_FOOTER_ENABLED,
         false
      ) && resolvedFrame.isPresent() && (!header.equals(Component.text("")) || !footer.equals(Component.text("")));
      boolean playerListNameEnabled = this.plugin.gameModeBehaviorService().booleanValue(
         CoreBehaviorKeys.TAB_PLAYER_LIST_NAME_ENABLED,
         false
      ) && !playerListName.equals(Component.text(""));

      return new TabListService.TabListSnapshot(
         "chat-format",
         header,
         footer,
         playerListName,
         headerFooterEnabled,
         playerListNameEnabled,
         resolvedFrame.isPresent(),
         false,
         false
      );
   }

   private Component resolveDefaultPlayerListName(Player player) {
      ChatFormatService formats = this.plugin.chatFormatService();
      if (formats == null) {
         return Component.text(player.getName());
      }
      return formats.renderPlayerListName(player);
   }

   private Optional<HudProvider.TabFrame> resolveProviderTabFrame(
      HudProvider provider,
      Player player,
      RankRecord rank,
      Component defaultPlayerListName
   ) {
      try {
         HudProvider.Context context = this.plugin.hudProviderService().createContext(
            player,
            rank,
            Map.of(),
            System.currentTimeMillis()
         );
         return provider.tabList(new HudProvider.TabListContext(
            context,
            Component.text(""),
            Component.text(""),
            defaultPlayerListName
         ));
      } catch (Exception ignored) {
         return Optional.empty();
      }
   }

   public static record TabListSnapshot(
      String templateId,
      Component header,
      Component footer,
      Component playerListName,
      boolean headerFooterEnabled,
      boolean playerListNameEnabled,
      boolean providerApplied,
      boolean configTemplatesEnabled,
      boolean configDrivenTemplate
   ) {
   }
}
