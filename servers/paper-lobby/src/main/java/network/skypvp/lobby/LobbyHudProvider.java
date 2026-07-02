package network.skypvp.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.gamemode.api.HudProvider.ActionBarContext;
import network.skypvp.paper.gamemode.api.HudProvider.BossBarContext;
import network.skypvp.paper.gamemode.api.HudProvider.BossBarFrame;
import network.skypvp.paper.gamemode.api.HudProvider.Context;
import network.skypvp.paper.gamemode.api.HudProvider.ScoreboardContext;
import network.skypvp.paper.gamemode.api.HudProvider.ScoreboardFrame;
import network.skypvp.paper.gamemode.api.HudProvider.TabFrame;
import network.skypvp.paper.gamemode.api.HudProvider.TabListContext;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.ServerTextUtil;
import network.skypvp.shared.AnimatedText.Effect;

public final class LobbyHudProvider implements HudProvider {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage

   public LobbyHudProvider() {
   }

   public String modeKey() {
      return "lobby";
   }

   public Optional<Component> actionBar(ActionBarContext context) {
      return Optional.empty();
   }

   public Optional<ScoreboardFrame> scoreboard(ScoreboardContext context) {
      return Optional.empty();
   }

   public Optional<TabFrame> tabList(TabListContext context) {
      return Optional.empty();
   }

   public Optional<BossBarFrame> bossBar(BossBarContext context) {
      return Optional.empty();
   }

   private static String humanize(String value, String fallback) {
      String source = value == null ? "" : value.trim();
      if (source.isBlank()) {
         source = fallback;
      }

      if (source != null && !source.isBlank()) {
         StringBuilder out = new StringBuilder();

         for (String part : source.replace('_', ' ').split("\\s+")) {
            if (!part.isBlank()) {
               if (!out.isEmpty()) {
                  out.append(' ');
               }

               String normalized = part.toLowerCase(Locale.ROOT);
               out.append(Character.toUpperCase(normalized.charAt(0))).append(normalized.substring(1));
            }
         }

         return out.toString();
      } else {
         return "";
      }
   }

   private static String escape(String text) {
      return text.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
   }
}
