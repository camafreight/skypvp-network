package network.skypvp.paper.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenu;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.paper.gui.PaginatedGuiMenu;
import network.skypvp.paper.chat.ChatChannelAccess;
import network.skypvp.paper.integration.ProxySocialMessenger;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.model.PlayerSocialSettings;
import network.skypvp.paper.repository.PlayerStatsRepository;
import network.skypvp.paper.repository.SocialGraphRepository;
import network.skypvp.shared.chat.ChatChannel;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class SocialMenuService {
    private static final List<Integer> FRIEND_PAGE_SLOTS = IntStream.range(9, 45).boxed().toList();
    private static final int FRIEND_BACK_SLOT = 0;
    private static final int FRIEND_NEXT_SLOT = 8;
    private static final int FRIEND_PREVIOUS_SLOT = 45;

    private final PaperCorePlugin plugin;
    private final GuiManager guiManager;
    private final SocialGraphRepository socialGraphRepository;
    private final PlayerSocialSettingsService socialSettingsService;
    private final PlayerStatsRepository playerStatsRepository;
    private final Consumer<Player> openRootMenu;
    private final Map<UUID, List<SocialGraphRepository.SocialPlayer>> friendsCache = new ConcurrentHashMap<>();

    public SocialMenuService(
            PaperCorePlugin plugin,
            GuiManager guiManager,
            SocialGraphRepository socialGraphRepository,
            PlayerSocialSettingsService socialSettingsService,
            PlayerStatsRepository playerStatsRepository,
            Consumer<Player> openRootMenu
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.guiManager = Objects.requireNonNull(guiManager, "guiManager");
        this.socialGraphRepository = socialGraphRepository;
        this.socialSettingsService = Objects.requireNonNull(socialSettingsService, "socialSettingsService");
        this.playerStatsRepository = playerStatsRepository;
        this.openRootMenu = Objects.requireNonNull(openRootMenu, "openRootMenu");
    }

    public void openSocialHub(Player player) {
        if (this.socialGraphRepository == null) {
            this.notifySocialUnavailable(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        this.socialSettingsService.refresh(playerId).thenAcceptAsync(
                settings -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    this.guiManager.open(player, this.buildSocialHubMenu(player, settings));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    PlayerSocialSettings settings = this.socialSettingsService.get(playerId);
                    this.guiManager.open(player, this.buildSocialHubMenu(player, settings));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }
            });
            return null;
        });
    }

    public void openFriendsMenu(Player player) {
        if (this.socialGraphRepository == null) {
            this.notifySocialUnavailable(player);
            return;
        }
        player.sendActionBar(Component.text("Loading friends...", NamedTextColor.GRAY));
        this.socialGraphRepository.listFriends(player.getUniqueId()).thenAcceptAsync(
                friends -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendActionBar(Component.empty());
                    this.friendsCache.put(player.getUniqueId(), friends);
                    this.guiManager.open(player, this.buildFriendsMenu(player));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendActionBar(Component.empty());
                    NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not open friends right now. Try again.</red>"));
                }
            });
            return null;
        });
    }

    private GuiMenu buildSocialHubMenu(Player player, PlayerSocialSettings settings) {
        GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text("Socials"), 54);
        menu.button(FRIEND_BACK_SLOT, GuiButtonLibrary.backToMainMenu(), context -> this.openRootMenu.accept(context.viewer()));
        menu.button(
                20,
                GuiButtonLibrary.toggle(
                        settings.chatEnabled(),
                        "Chat Toggle",
                        lore -> {
                            lore.plain("When off, you will not see player chat.");
                            lore.plain("Staff chat and announcements remain visible.");
                            lore.plain("Bypass: skypvp.chat.toggle.bypass");
                        }
                ),
                context -> this.toggleBooleanSetting(context, PlayerSocialSettings::chatEnabled, PlayerSocialSettings::withChatEnabled)
        );
        menu.button(
                21,
                GuiButtonLibrary.toggle(
                        settings.blockFriendRequests(),
                        "Block Friend Requests",
                        lore -> lore.plain("When on, other players cannot send you friend requests.")
                ),
                context -> this.toggleBooleanSetting(context, PlayerSocialSettings::blockFriendRequests, PlayerSocialSettings::withBlockFriendRequests)
        );
        menu.button(
                22,
                GuiButtonLibrary.toggle(
                        settings.blockPartyRequests(),
                        "Block Party Requests",
                        lore -> lore.plain("When on, other players cannot invite you to parties.")
                ),
                context -> this.toggleBooleanSetting(context, PlayerSocialSettings::blockPartyRequests, PlayerSocialSettings::withBlockPartyRequests)
        );
        menu.button(
                23,
                GuiButtonLibrary.toggle(
                        settings.profanityFilterEnabled(),
                        "Profanity Censorship",
                        lore -> lore.plain("When on, bad language in chat is replaced with asterisks.")
                ),
                context -> this.toggleBooleanSetting(context, PlayerSocialSettings::profanityFilterEnabled, PlayerSocialSettings::withProfanityFilterEnabled)
        );
        menu.button(
                24,
                GuiButtonLibrary.toggle(
                        settings.autoTranslateEnabled(),
                        "Auto Translate",
                        lore -> {
                            lore.plain("When on, chat from players who use a different");
                            lore.plain("Minecraft language is translated for you.");
                            lore.plain("Uses your client language from game settings.");
                        }
                ),
                context -> this.toggleBooleanSetting(context, PlayerSocialSettings::autoTranslateEnabled, PlayerSocialSettings::withAutoTranslateEnabled)
        );
        menu.button(
                31,
                GuiButtonLibrary.primaryAction(
                        Material.WRITABLE_BOOK,
                        "Chat Toggle Cycle: " + settings.activeChatChannel().displayName(),
                        lore -> {
                            lore.plain("Choose where your chat messages are sent.");
                            lore.plain("Current: " + settings.activeChatChannel().displayName());
                            lore.plain("Click to cycle (same as /chat toggle).");
                        }
                ),
                context -> this.cycleChatChannel(context)
        );
        menu.button(
                32,
                GuiButtonLibrary.primaryAction(Material.PLAYER_HEAD, "Friends", lore -> {
                    lore.plain("Browse your friends list.");
                    lore.plain("Click a friend to view their profile.");
                }),
                context -> this.openFriendsMenu(context.viewer())
        );
        return menu.build();
    }

    private PaginatedGuiMenu<SocialGraphRepository.SocialPlayer> buildFriendsMenu(Player player) {
        return PaginatedGuiMenu.<SocialGraphRepository.SocialPlayer>create(Component.text("Friends"), 54)
                .pageSlots(FRIEND_PAGE_SLOTS)
                .entries(viewer -> this.friendsCache.getOrDefault(viewer.getUniqueId(), List.of()))
                .renderItem((viewer, friend) -> GuiItems.playerHead(
                        friend.playerId(),
                        "<#FFFFFF>" + friend.username(),
                        GuiTextLibrary.lore().raw("<#888888>Click to view profile").build()
                ))
                .onItemClick((context, friend) -> this.openFriendProfile(context.viewer(), friend))
                .button(FRIEND_BACK_SLOT, viewer -> GuiButtonLibrary.backToMainMenu(), context -> this.openRootMenu.accept(context.viewer()))
                .previousButton(FRIEND_PREVIOUS_SLOT, GuiButtonLibrary::previousPage)
                .nextButton(FRIEND_NEXT_SLOT, GuiButtonLibrary::nextPage)
                .build();
    }

    private void openFriendProfile(Player viewer, SocialGraphRepository.SocialPlayer friend) {
        viewer.sendActionBar(Component.text("Loading profile...", NamedTextColor.GRAY));
        PlayerStatsRepository statsRepository = this.playerStatsRepository != null
                ? this.playerStatsRepository
                : this.plugin.playerStatsRepository();
        if (statsRepository == null) {
            this.plugin.platformScheduler().runOnPlayer(viewer, () -> {
                viewer.sendActionBar(Component.empty());
                this.guiManager.open(viewer, this.buildFriendProfileMenu(viewer, friend, null));
                NetworkSoundCue.UI_BUTTON_CLICK.play(viewer);
            });
            return;
        }
        this.plugin.platformScheduler().runAsync(() -> {
            PlayerStatsRepository.PlayerStats stats = statsRepository.getStats(friend.playerId()).orElse(null);
            this.plugin.platformScheduler().runOnPlayer(viewer, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                viewer.sendActionBar(Component.empty());
                this.guiManager.open(viewer, this.buildFriendProfileMenu(viewer, friend, stats));
                NetworkSoundCue.UI_BUTTON_CLICK.play(viewer);
            });
        });
    }

    private GuiMenu buildFriendProfileMenu(
            Player viewer,
            SocialGraphRepository.SocialPlayer friend,
            PlayerStatsRepository.PlayerStats stats
    ) {
        GuiMenuBuilder menu = GuiMenuBuilder.create(Component.text(friend.username()), 54);
        menu.button(FRIEND_BACK_SLOT, GuiButtonLibrary.back("Return to friends"), context -> this.openFriendsMenu(context.viewer()));
        menu.button(
                4,
                GuiItems.playerHead(
                        friend.playerId(),
                        "<#FFD700>" + friend.username(),
                        GuiTextLibrary.lore().raw("<#888888>Friend profile").build()
                ),
                context -> {
                }
        );
        int statsSlot = 22;
        if (stats == null) {
            menu.button(
                    statsSlot,
                    GuiButtonLibrary.infoExclamation("No stats yet", lore -> lore.plain("This player has no recorded stats.")),
                    context -> {
                    }
            );
        } else {
            menu.button(
                    statsSlot,
                    GuiButtonLibrary.infoQuestion(
                            "Stats",
                            lore -> {
                                lore.fact("Kills", String.valueOf(stats.kills()));
                                lore.fact("Deaths", String.valueOf(stats.deaths()));
                                lore.fact("K/D", String.format("%.2f", stats.method_255()));
                                lore.fact("Playtime", stats.playtimeMinutes() + " min");
                                lore.fact("Duel W/L", stats.duelWins() + " / " + stats.duelLosses());
                            }
                    ),
                    context -> {
                    }
            );
        }
        menu.button(
                40,
                GuiButtonLibrary.warningAction(Material.BARRIER, "Remove Friend", lore -> {
                    lore.plain("Stop being friends with " + friend.username() + ".");
                    lore.footer("<#FF5555>", "Click to remove");
                }),
                context -> this.removeFriend(context, friend)
        );
        return menu.build();
    }

    private void cycleChatChannel(GuiClickContext context) {
        Player player = context.viewer();
        PlayerSocialSettings settings = this.socialSettingsService.get(player.getUniqueId());
        ChatChannel next = ChatChannelAccess.nextChannel(player, settings.activeChatChannel());
        this.socialSettingsService.setActiveChatChannel(player.getUniqueId(), next).thenAcceptAsync(
                saved -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    context.reopen(this.buildSocialHubMenu(player, saved));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not save chat toggle cycle.</red>"));
                }
            });
            return null;
        });
    }

    private void toggleBooleanSetting(
            GuiClickContext context,
            Function<PlayerSocialSettings, Boolean> getter,
            ToggleMutator mutator
    ) {
        Player player = context.viewer();
        UUID playerId = player.getUniqueId();
        PlayerSocialSettings current = this.socialSettingsService.get(playerId);
        PlayerSocialSettings updated = mutator.apply(current, !getter.apply(current));
        this.persistSetting(context, player, updated);
    }

    private void persistSetting(GuiClickContext context, Player player, PlayerSocialSettings settings) {
        UUID playerId = player.getUniqueId();
        this.socialSettingsService.update(playerId, settings).thenAcceptAsync(
                saved -> this.plugin.platformScheduler().runOnPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    context.reopen(this.buildSocialHubMenu(player, saved));
                    NetworkSoundCue.UI_BUTTON_CLICK.play(player);
                }),
                runnable -> this.plugin.platformScheduler().runAsync(runnable)
        ).exceptionally(error -> {
            this.plugin.platformScheduler().runOnPlayer(player, () -> {
                if (player.isOnline()) {
                    NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Could not save social setting.</red>"));
                }
            });
            return null;
        });
    }

    @FunctionalInterface
    private interface ToggleMutator {
        PlayerSocialSettings apply(PlayerSocialSettings settings, boolean value);
    }

    private void removeFriend(GuiClickContext context, SocialGraphRepository.SocialPlayer friend) {
        Player player = context.viewer();
        ProxySocialMessenger.sendFriendCommand(this.plugin, player, "remove " + friend.username());
        this.friendsCache.remove(player.getUniqueId());
        NetworkSoundCue.UI_BUTTON_CLICK.play(player);
        player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>Removed <white>" + friend.username() + "<yellow> from your friends."));
        this.openFriendsMenu(player);
    }

    private void notifySocialUnavailable(Player player) {
        NetworkSoundCue.UI_BUTTON_FAILURE.play(player);
        player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Friends and social settings are unavailable right now.</red>"));
    }
}
