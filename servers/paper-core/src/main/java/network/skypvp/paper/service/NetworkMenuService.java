package network.skypvp.paper.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.NetworkMenuAccess;
import network.skypvp.paper.gui.GuiAnvilPrompt;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.paper.integration.ProxySocialMessenger;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.repository.PartyRole;
import network.skypvp.paper.repository.SocialGraphRepository;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class NetworkMenuService {

   private static final int[] PARTY_HEAD_SLOTS = {47, 48, 49, 50, 51};
   private static final int[] PARTY_LEADER_SLOTS = {20, 21, 22, 23, 24};
   private static final int[] PARTY_ROLE_SLOTS = {38, 39, 40, 41, 42};

   private final PaperCorePlugin plugin;
   private final GuiManager guiManager;
   private final SocialGraphRepository socialGraphRepository;
   private final PlayerInventoryManager inventoryManager;
   private final RewardClaimMenuService rewardClaimMenuService;
   private SocialMenuService socialMenuService;

   public NetworkMenuService(
      PaperCorePlugin plugin,
      GuiManager guiManager,
      SocialGraphRepository socialGraphRepository,
      PlayerInventoryManager inventoryManager,
      RewardClaimMenuService rewardClaimMenuService
   ) {
      this.plugin = Objects.requireNonNull(plugin, "plugin");
      this.guiManager = Objects.requireNonNull(guiManager, "guiManager");
      this.socialGraphRepository = socialGraphRepository;
      this.inventoryManager = inventoryManager;
      this.rewardClaimMenuService = rewardClaimMenuService;
   }

   public void bindSocialMenuService(SocialMenuService socialMenuService) {
      this.socialMenuService = socialMenuService;
   }

   public void openRootMenu(Player player) {
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Network Menu"), 54);
      menu.button(0, GuiButtonLibrary.close("Close menu"), GuiClickContext::close);

      this.addRootButton(menu, player, 19, Material.PIGLIN_HEAD, "Party", "Party management", "PARTY",
            context -> this.openPartyMenu(context.viewer()));
      this.addRootButton(menu, player, 20, Material.PAPER, "Socials", "Social settings and friends", "SOCIALS",
            context -> this.openSocialsMenu(context.viewer()));
      this.addRootButton(menu, player, 24, Material.NETHERITE_HELMET, "Loadouts", "Save and load gear kits", "LOADOUTS",
            context -> this.openLoadouts(context));
      this.addRootButton(menu, player, 25, Material.ENDER_CHEST, "Vault", "Stored items", "VAULT",
            context -> this.openVault(context));
      this.addRootButton(menu, player, 40, Material.GOLD_INGOT, "Rewards", "Unclaimed rewards", "REWARDS",
            context -> this.rewardClaimMenuService.openRewardsMenu(context.viewer()));

      this.guiManager.open(player, menu.build());
   }

   private void addRootButton(
      GuiMenuBuilder menu,
      Player player,
      int slot,
      Material material,
      String title,
      String summary,
      String submenuKey,
      java.util.function.Consumer<GuiClickContext> onOpen
   ) {
      if (this.isSubmenuLocked(player, submenuKey)) {
         menu.button(slot, GuiButtonLibrary.locked(Material.BARRIER, title, this.lockReason(player, submenuKey)), context -> {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
            context.viewer().sendMessage(MiniMessage.miniMessage().deserialize(
                  "<red>" + title + " is locked. " + this.lockReason(player, submenuKey) + "</red>"
            ));
         });
         return;
      }
      menu.button(slot, GuiButtonLibrary.primaryAction(material, title, lore -> lore.plain(summary)), onOpen);
   }

   public void openSocialsMenu(Player player) {
      if (this.isSubmenuLocked(player, "SOCIALS")) {
         this.notifyLocked(player, "Socials");
         return;
      }
      if (this.socialMenuService != null) {
         this.socialMenuService.openSocialHub(player);
         return;
      }
      this.notifySocialUnavailable(player);
   }

   public void openPartyMenu(Player player) {
      if (this.isSubmenuLocked(player, "PARTY")) {
         this.notifyLocked(player, "Party");
         return;
      }
      if (this.socialGraphRepository == null) {
         this.notifySocialUnavailable(player);
         return;
      }
      player.sendActionBar(Component.text("Loading party...", NamedTextColor.GRAY));
      this.socialGraphRepository.partyForMember(player.getUniqueId()).thenAcceptAsync(
         partyOptional -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
               return;
            }
            player.sendActionBar(Component.empty());
            if (partyOptional.isEmpty()) {
               this.openEmptyPartyMenu(player);
               return;
            }
            this.openPartyMenu(player, partyOptional.get());
            NetworkSoundCue.UI_BUTTON_CLICK.play(player);
         }),
         runnable -> this.plugin.platformScheduler().runAsync(runnable)
      ).exceptionally(error -> {
         this.plugin.platformScheduler().runOnPlayer(player, () -> {
            if (player.isOnline()) {
               player.sendActionBar(Component.empty());
               NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
               player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not open party right now. Try again.</red>"));
            }
         });
         return null;
      });
   }

   private void openEmptyPartyMenu(Player player) {
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Party"), 54);
      menu.button(8, GuiButtonLibrary.back("Return to the network menu"), context -> this.openRootMenu(context.viewer()));
      menu.button(
         4,
         GuiButtonLibrary.infoCard(
            Material.OAK_SIGN,
            "Party Roles",
            lore -> this.appendPartyRoleGuide(lore)
         ),
         context -> {
         }
      );
      menu.button(
         22,
         GuiButtonLibrary.positiveAction(Material.LIME_BANNER, "Create Party", lore -> lore
               .plain("Start a new party with you as leader.")
               .plain("Search for a player to invite right away.")
               .footer("<#888888>", "Or use /party create")),
         this::openPartyCreatePrompt
      );
      this.guiManager.open(player, menu.build());
   }

   private void openPartyMenu(Player player, SocialGraphRepository.PartySnapshot party) {
      boolean isLeader = party.leaderId().equals(player.getUniqueId());
      GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Party"), 54);
      menu.button(8, GuiButtonLibrary.back("Return to the network menu"), context -> this.openRootMenu(context.viewer()));

      menu.button(
         0,
         GuiButtonLibrary.secondaryAction(
            Material.COMPASS,
            "Party Settings",
            lore -> lore
                  .fact("Follow Leader", party.followLeader() ? "ON" : "OFF")
                  .plain("Automatically follow the party leader between servers.")
                  .footer(isLeader ? "<#888888>" : "<red>", isLeader ? "Click to toggle" : "Leader only")
         ),
         context -> {
            if (!isLeader) {
               NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
               context.viewer().sendMessage(MiniMessage.miniMessage().deserialize("<red>Only the party leader can change this setting.</red>"));
               return;
            }
            this.socialGraphRepository.setPartyFollowLeader(player.getUniqueId(), !party.followLeader())
               .thenAccept(success -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                  if (!player.isOnline()) {
                     return;
                  }
                  if (Boolean.TRUE.equals(success)) {
                     this.openPartyMenu(player);
                  } else {
                     NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                     player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not update party settings.</red>"));
                  }
               }));
         }
      );

      menu.button(
         4,
         GuiButtonLibrary.infoCard(Material.OAK_SIGN, "Party Roles", lore -> this.appendPartyRoleGuide(lore)),
         context -> {
         }
      );

      if (isLeader) {
         menu.button(
            6,
            GuiButtonLibrary.warningAction(Material.BARRIER, "Disband Party", lore -> lore
                  .plain("Disbands the party for everyone.")
                  .footer("<#888888>", "Or use /party disband")),
            context -> context.viewer().performCommand("party disband")
         );
      } else {
         menu.button(
            6,
            GuiButtonLibrary.warningAction(Material.IRON_DOOR, "Leave Party", lore -> lore
                  .plain("Leave this party.")
                  .footer("<#888888>", "Or use /party leave")),
            context -> context.viewer().performCommand("party leave")
         );
      }

      List<SocialGraphRepository.PartyMember> members = party.members();
      int columns = Math.min(members.size(), PARTY_HEAD_SLOTS.length);
      for (int index = 0; index < columns; index++) {
         SocialGraphRepository.PartyMember member = members.get(index);
         int headSlot = PARTY_HEAD_SLOTS[index];
         int leaderSlot = PARTY_LEADER_SLOTS[index];
         int roleSlot = PARTY_ROLE_SLOTS[index];

         menu.button(
            headSlot,
            GuiItems.playerHead(
               member.playerId(),
               member.username(),
               GuiTextLibrary.standardLore(List.of(
                  "<#888888>" + member.role().displayName(),
                  member.leader() ? "<gold>Party leader" : "<gray>Click dyes above to manage"
               ))
            ),
            context -> {
            }
         );

         Material leaderMaterial = member.leader() ? Material.LIME_DYE : Material.GRAY_DYE;
         menu.button(
            leaderSlot,
            GuiButtonLibrary.secondaryAction(
               leaderMaterial,
               member.leader() ? "Leader" : "Promote Leader",
               lore -> lore
                  .plain(member.leader() ? "Current party leader." : "Make this player the party leader.")
                  .footer(isLeader ? "<#888888>" : "<red>", isLeader ? "Click to transfer leadership" : "Leader only")
            ),
            context -> {
               if (!isLeader || member.leader()) {
                  return;
               }
               this.socialGraphRepository.transferPartyLeadership(player.getUniqueId(), member.playerId())
                  .thenAccept(success -> this.refreshPartyMenu(player, success, "Could not transfer leadership."));
            }
         );

         Material roleMaterial = this.roleMaterial(member.role());
         menu.button(
            roleSlot,
            GuiButtonLibrary.secondaryAction(
               roleMaterial,
               member.role().displayName(),
               lore -> lore
                  .plain(this.roleDescription(member.role()))
                  .footer(isLeader && !member.leader() ? "<#888888>" : "<red>", isLeader && !member.leader()
                        ? "Click to cycle role"
                        : "Leader only")
            ),
            context -> {
               if (!isLeader || member.leader()) {
                  return;
               }
               PartyRole nextRole = member.role().cycleRank();
               this.socialGraphRepository.updatePartyMemberRole(player.getUniqueId(), member.playerId(), nextRole)
                  .thenAccept(success -> this.refreshPartyMenu(player, success, "Could not update role."));
            }
         );
      }

      PartyRole viewerRole = party.members().stream()
         .filter(member -> member.playerId().equals(player.getUniqueId()))
         .map(SocialGraphRepository.PartyMember::role)
         .findFirst()
         .orElse(PartyRole.MEMBER);
      if (viewerRole.canInvite()) {
         menu.button(
            2,
            GuiButtonLibrary.primaryAction(Material.PAPER, "Invite Players", lore -> lore
                  .plain("Search for a player to invite to your party.")
                  .plain("Trusted members and co-leaders can also invite.")),
            this::openPartyInvitePrompt
         );
      }

      this.guiManager.open(player, menu.build());
   }

   private void refreshPartyMenu(Player player, boolean success, String failureMessage) {
      this.plugin.platformScheduler().runOnPlayer(player, () -> {
         if (!player.isOnline()) {
            return;
         }
         if (success) {
            this.openPartyMenu(player);
         } else {
            NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + failureMessage + "</red>"));
         }
      });
   }

   private void appendPartyRoleGuide(GuiTextLibrary.LoreBuilder lore) {
      lore.plain("Leader — full control, invites, roles, disband.");
      lore.plain("Co-Leader — can invite, start breaches, and help manage.");
      lore.plain("Trusted — can invite players to the party.");
      lore.plain("Member — joins raids with the party.");
      lore.footer("<#888888>", "Dyes above heads set leader and roles");
   }

   private void openPartyCreatePrompt(GuiClickContext context) {
      context.openAnvilPrompt(GuiAnvilPrompt.builder(Component.text("Create Party"))
            .inputItem(GuiItems.named(
                  Material.LIME_BANNER,
                  GuiTextLibrary.title("#55FF55", "Create Party"),
                  GuiTextLibrary.lore()
                        .plain("Type a player name to invite.")
                        .plain("Leave blank to create an empty party.")
                        .footerStrong("<yellow>", "Take result to create")
                        .build()
            ))
            .onSubmit((player, text) -> {
               ProxySocialMessenger.sendPartyCommand(this.plugin, player, "create");
               String inviteTarget = text == null ? "" : text.trim();
               if (!inviteTarget.isBlank()) {
                  ProxySocialMessenger.sendPartyCommand(this.plugin, player, "invite " + inviteTarget);
               }
               NetworkSoundCue.UI_BUTTON_CLICK.play(player);
               this.plugin.platformScheduler().runOnPlayerLater(player, () -> this.openPartyMenu(player), 10L);
            })
            .build());
   }

   private void openPartyInvitePrompt(GuiClickContext context) {
      context.openAnvilPrompt(GuiAnvilPrompt.builder(Component.text("Invite Player"))
            .inputItem(GuiItems.named(
                  Material.PLAYER_HEAD,
                  GuiTextLibrary.title("#FFD700", "Invite Player"),
                  GuiTextLibrary.lore()
                        .plain("Search for a player to invite.")
                        .footerStrong("<yellow>", "Take result to invite")
                        .build()
            ))
            .onSubmit((player, text) -> {
               String inviteTarget = text == null ? "" : text.trim();
               if (inviteTarget.isBlank()) {
                  NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                  player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Enter a player name to invite.</red>"));
                  this.openPartyMenu(player);
                  return;
               }
               ProxySocialMessenger.sendPartyCommand(this.plugin, player, "invite " + inviteTarget);
               NetworkSoundCue.UI_BUTTON_CLICK.play(player);
               this.plugin.platformScheduler().runOnPlayerLater(player, () -> this.openPartyMenu(player), 10L);
            })
            .build());
   }

   private Material roleMaterial(PartyRole role) {
      return switch (role) {
         case LEADER -> Material.LIME_DYE;
         case CO_LEADER -> Material.ORANGE_DYE;
         case TRUSTED -> Material.YELLOW_DYE;
         case MEMBER -> Material.GRAY_DYE;
      };
   }

   private String roleDescription(PartyRole role) {
      return switch (role) {
         case LEADER -> "Full party control.";
         case CO_LEADER -> "Can invite, start breaches, and help manage members.";
         case TRUSTED -> "Can invite players to the party.";
         case MEMBER -> "Standard party member.";
      };
   }

   public void openMenuByKey(Player player, String menuKey) {
      if (menuKey == null) {
         return;
      }
      switch (menuKey.toUpperCase()) {
         case "FRIENDS":
            if (this.isSubmenuLocked(player, "SOCIALS")) {
               this.notifyLocked(player, "Friends");
               return;
            }
            if (this.socialMenuService != null) {
               this.socialMenuService.openFriendsMenu(player);
            } else {
               this.openSocialsMenu(player);
            }
            break;
         case "PARTY":
            this.openPartyMenu(player);
            break;
         case "REWARDS":
            if (this.isSubmenuLocked(player, "REWARDS")) {
               this.notifyLocked(player, "Rewards");
               return;
            }
            this.rewardClaimMenuService.openRewardsMenu(player);
            break;
         case "LOADOUT":
         case "LOADOUTS":
            if (this.isSubmenuLocked(player, "LOADOUTS")) {
               this.notifyLocked(player, "Loadouts");
               return;
            }
            this.openLoadouts(player);
            break;
         case "VAULT":
            if (this.isSubmenuLocked(player, "VAULT")) {
               this.notifyLocked(player, "Vault");
               return;
            }
            this.openVault(player);
            break;
         default:
            this.openRootMenu(player);
      }
   }

   private void openLoadouts(GuiClickContext context) {
      if (this.isSubmenuLocked(context.viewer(), "LOADOUTS")) {
         this.notifyLocked(context.viewer(), "Loadouts");
         return;
      }
      if (this.inventoryManager == null) {
         this.notifyInventoryUnavailable(context);
         return;
      }
      this.inventoryManager.openLoadoutMenu(context);
   }

   private void openLoadouts(Player player) {
      if (this.isSubmenuLocked(player, "LOADOUTS")) {
         this.notifyLocked(player, "Loadouts");
         return;
      }
      if (this.inventoryManager == null) {
         this.notifyInventoryUnavailable(player);
         return;
      }
      NetworkSoundCue.UI_BUTTON_CLICK.play(player);
      this.inventoryManager.openLoadoutMenu(player);
   }

   private void openVault(GuiClickContext context) {
      if (this.isSubmenuLocked(context.viewer(), "VAULT")) {
         this.notifyLocked(context.viewer(), "Vault");
         return;
      }
      if (this.inventoryManager == null) {
         this.notifyInventoryUnavailable(context);
         return;
      }
      NetworkSoundCue.UI_BUTTON_CLICK.play(context.viewer());
      this.inventoryManager.openVaultMenu(context.viewer(), true);
   }

   private void openVault(Player player) {
      if (this.isSubmenuLocked(player, "VAULT")) {
         this.notifyLocked(player, "Vault");
         return;
      }
      if (this.inventoryManager == null) {
         this.notifyInventoryUnavailable(player);
         return;
      }
      NetworkSoundCue.UI_BUTTON_CLICK.play(player);
      this.inventoryManager.openVaultMenu(player, true);
   }

   private boolean isSubmenuLocked(Player player, String submenuKey) {
      for (NetworkMenuAccess access : this.menuAccessProviders()) {
         if (access.isHubSubmenuLocked(player, submenuKey)) {
            return true;
         }
      }
      return false;
   }

   private String lockReason(Player player, String submenuKey) {
      for (NetworkMenuAccess access : this.menuAccessProviders()) {
         if (access.isHubSubmenuLocked(player, submenuKey)) {
            return access.hubSubmenuLockReason(player);
         }
      }
      return "Unavailable during a raid.";
   }

   private String lockReason(Player player) {
      return this.lockReason(player, "VAULT");
   }

   private Collection<NetworkMenuAccess> menuAccessProviders() {
      return this.plugin.getServer().getServicesManager().getRegistrations(NetworkMenuAccess.class).stream()
         .map(registration -> registration.getProvider())
         .toList();
   }

   private void notifyLocked(Player player, String label) {
      NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
      player.sendMessage(MiniMessage.miniMessage().deserialize(
            "<red>" + label + " is locked. " + this.lockReason(player) + "</red>"
      ));
   }

   private void notifySocialUnavailable(Player player) {
      NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
      player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Friends and party menus are unavailable right now.</red>"));
   }

   private void notifyInventoryUnavailable(GuiClickContext context) {
      NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
      context.viewer().sendMessage(MiniMessage.miniMessage().deserialize("<red>Vault and loadouts are unavailable right now.</red>"));
   }

   private void notifyInventoryUnavailable(Player player) {
      NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
      player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Vault and loadouts are unavailable right now.</red>"));
   }
}
