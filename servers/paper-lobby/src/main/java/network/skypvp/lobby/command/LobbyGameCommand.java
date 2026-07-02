package network.skypvp.lobby.command;

import network.skypvp.shared.ServerTextUtil;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.lobby.game.DuelsModule;
import network.skypvp.lobby.game.HideAndSeekModule;
import network.skypvp.lobby.game.LobbyGameManager;
import network.skypvp.lobby.game.LobbyGameType;
import network.skypvp.lobby.game.TagModule;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiLayoutLibrary.Dashboard27;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class LobbyGameCommand implements CommandExecutor {
   private final LobbyGameManager manager;
   private final DuelsModule duels;
   // $VF: renamed from: tag network.SkyPvP.lobby.game.TagModule
   private final TagModule tag;
   // $VF: renamed from: hns network.SkyPvP.lobby.game.HideAndSeekModule
   private final HideAndSeekModule hns;

   public LobbyGameCommand(LobbyGameManager manager, DuelsModule duels, TagModule tag, HideAndSeekModule hns) {
      this.manager = manager;
      this.duels = duels;
      this.tag = tag;
      this.hns = hns;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (sender instanceof Player p) {
         if (args.length == 0) {
            p.sendMessage(ServerTextUtil.component("&cUsage: /lobbygame <tag|hns|leave>"));
            return true;
         } else {
            String action = args[0].toLowerCase();
            if (action.equals("leave")) {
               LobbyGameType type = this.manager.getActiveGame(p.getUniqueId());
               if (type == LobbyGameType.NONE) {
                  p.sendMessage(ServerTextUtil.component("&cYou are not in a game."));
                  return true;
               } else {
                  switch (type) {
                     case DUELS:
                        this.duels.endDuel(p);
                        break;
                     case TAG:
                        this.tag.leaveTag(p);
                        break;
                     case HIDE_AND_SEEK:
                        this.hns.leaveGame(p);
                        break;
                     case PARKOUR:
                        this.manager.quitGame(p);
                        p.sendMessage(ServerTextUtil.component("&eYou left Parkour."));
                  }

                  return true;
               }
            } else if (action.equals("menu")) {
               PaperCorePlugin core = (PaperCorePlugin)PaperCorePlugin.getPlugin(PaperCorePlugin.class);
               if (core.guiManager() != null) {
                  core.guiManager()
                     .open(
                        p,
                        GuiMenuBuilder.create(MiniMessage.miniMessage().deserialize("<#ff00ff><bold>Lobby Games</bold></#ff00ff>"), 27)
                           .button(
                              (Integer)Dashboard27.PRIMARY_SLOTS.get(0),
                              GuiButtonLibrary.primaryAction(
                                 Material.STICK,
                                 "Knockback Tag",
                                 lore -> lore.bullet("Tag players with knockback hits.")
                                       .bullet("Use double jump and sprint burst.")
                                       .footerStrong("<yellow>", "Click to play")
                              ),
                              context -> {
                                 context.viewer().closeInventory();
                                 context.viewer().performCommand("lobbygame tag");
                              }
                           )
                           .button(
                              (Integer)Dashboard27.PRIMARY_SLOTS.get(3),
                              GuiButtonLibrary.primaryAction(
                                 Material.ENDER_PEARL,
                                 "Hide And Seek",
                                 lore -> lore.bullet("Hide as blocks or hunt seekers.")
                                       .bullet("Play quick lobby rounds.")
                                       .footerStrong("<yellow>", "Click to play")
                              ),
                              context -> {
                                 context.viewer().closeInventory();
                                 context.viewer().performCommand("lobbygame hns");
                              }
                           )
                           .build()
                     );
               }

               return true;
            } else if (this.manager.isInGame(p.getUniqueId()) && this.manager.getActiveGame(p.getUniqueId()) != LobbyGameType.HIDE_AND_SEEK) {
               p.sendMessage(ServerTextUtil.component("&cYou must leave your current game first! (/lobbygame leave)"));
               return true;
            } else {
               switch (action) {
                  case "tag":
                     this.tag.toggleTag(p);
                     break;
                  case "hns":
                     this.hns.joinGame(p);
                     break;
                  default:
                     p.sendMessage(Component.text("Unknown game mode. Actions: tag, hns, leave", NamedTextColor.RED));
               }

               return true;
            }
         }
      } else {
         sender.sendMessage(ServerTextUtil.component("Players only."));
         return true;
      }
   }
}
