package network.skypvp.paper.service;

import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.gamemode.api.CoreBehaviorKeys;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;

public final class TabListService {
   private final PaperCorePlugin plugin;
   private final RankService rankService;

   public TabListService(PaperCorePlugin plugin, RankService rankService) {
      this.plugin = plugin;
      this.rankService = rankService;
   }

   public void refresh() {
      this.plugin.platformScheduler().runForEachPlayerOnGlobal(this::refreshPlayer);
   }

   public void refreshPlayer(Player player) {
      TabListService.TabListSnapshot snapshot = this.snapshot(player);
      if (snapshot.headerFooterEnabled()) {
         player.sendPlayerListHeaderAndFooter(snapshot.header(), snapshot.footer());
      }

      if (snapshot.playerListNameEnabled()) {
         player.playerListName(snapshot.playerListName());
      }
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
