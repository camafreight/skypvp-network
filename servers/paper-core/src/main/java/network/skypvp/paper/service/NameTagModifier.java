package network.skypvp.paper.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team.OptionStatus;

public interface NameTagModifier {
   void modify(Player var1, Player var2, NameTagModifier.NameTagResult var3);

   public static class NameTagResult {
      public String teamName;
      public Component prefix;
      public Component suffix;
      public OptionStatus nameTagVisibility;
      public OptionStatus collisionRule;

      public NameTagResult(String teamName, Component prefix, Component suffix, OptionStatus nameTagVisibility, OptionStatus collisionRule) {
         this.teamName = teamName;
         this.prefix = prefix;
         this.suffix = suffix;
         this.nameTagVisibility = nameTagVisibility;
         this.collisionRule = collisionRule;
      }
   }
}
