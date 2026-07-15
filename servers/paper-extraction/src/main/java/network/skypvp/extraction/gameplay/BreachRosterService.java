package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiItems;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.gui.GuiTextLibrary;
import network.skypvp.paper.library.NetworkSoundCue;
import network.skypvp.paper.repository.SocialGraphRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Lets a party leader pick which members deploy together on a breach when the party is larger than the squad cap
 * ({@link BreachEngine#BREACH_MAX_SQUAD}). The leader is always part of the squad. A confirmed pick is stored briefly
 * and consumed by the next {@link BreachEngine#play} call, so re-running play with a valid selection deploys straight
 * away instead of re-opening the picker.
 */
public final class BreachRosterService {

    /** How long a confirmed squad selection stays valid before it must be re-picked. */
    private static final long SELECTION_TTL_MILLIS = 60_000L;

    private static final int[] MEMBER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25
    };
    private static final int CONFIRM_SLOT = 49;
    private static final int CLOSE_SLOT = 45;
    private static final int INFO_SLOT = 4;

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public BreachRosterService(PaperCorePlugin core, BreachEngine engine) {
        this.core = core;
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Returns and clears a still-valid squad selection for the leader, or null if none/expired. */
    public Set<UUID> consumeSelection(UUID leaderId) {
        if (leaderId == null) {
            return null;
        }
        Selection selection = this.selections.remove(leaderId);
        if (selection == null || selection.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return selection.memberIds();
    }

    /**
     * Opens the squad picker for the leader. If the GUI system is unavailable, falls back to auto-selecting the first
     * {@code BREACH_MAX_SQUAD} members (leader first) and deploying immediately so a breach can never dead-end.
     */
    public void openRosterPicker(Player leader, SocialGraphRepository.PartySnapshot snapshot, String mapId) {
        if (leader == null || snapshot == null) {
            return;
        }
        GuiManager guiManager = this.core == null ? null : this.core.guiManager();
        if (guiManager == null) {
            this.autoSelectAndPlay(leader, snapshot, mapId);
            return;
        }
        Set<UUID> initial = new LinkedHashSet<>();
        initial.add(leader.getUniqueId());
        this.render(guiManager, leader, snapshot, mapId, initial);
    }

    private void render(GuiManager guiManager, Player leader, SocialGraphRepository.PartySnapshot snapshot, String mapId, Set<UUID> selected) {
        GuiMenuBuilder menu = GuiMenuBuilder.create(
            Component.text("Select Breach Squad", NamedTextColor.DARK_AQUA), 54);
        menu.button(CLOSE_SLOT, GuiButtonLibrary.close("Cancel"), GuiClickContext::close);
        menu.button(
            INFO_SLOT,
            GuiButtonLibrary.infoQuestion("Breach Squad", lore -> lore
                .fact("Picked", selected.size() + "/" + BreachEngine.BREACH_MAX_SQUAD)
                .plain("Choose up to " + BreachEngine.BREACH_MAX_SQUAD + " members to deploy with.")
                .plain("You are always in the squad.")
                .footer("<#888888>", "Members will be summoned to your raid")),
            context -> {}
        );

        List<SocialGraphRepository.PartyMember> members = new ArrayList<>(snapshot.members());
        int shown = Math.min(members.size(), MEMBER_SLOTS.length);
        for (int index = 0; index < shown; index++) {
            SocialGraphRepository.PartyMember member = members.get(index);
            UUID memberId = member.playerId();
            boolean isLeaderSelf = memberId.equals(leader.getUniqueId());
            boolean isSelected = selected.contains(memberId);
            boolean online = isOnlineForDisplay(memberId);

            String statusLine = isLeaderSelf
                ? "<gold>You (always deploys)"
                : (isSelected ? "<green>\u2714 In squad" : "<gray>Click to add");
            String presenceLine = online ? "<green>Online" : "<#888888>Will be summoned if online";

            menu.button(
                MEMBER_SLOTS[index],
                GuiItems.playerHead(
                    memberId,
                    member.username(),
                    GuiTextLibrary.standardLore(List.of(
                        "<#888888>" + member.role().displayName(),
                        presenceLine,
                        statusLine
                    ))
                ),
                context -> {
                    if (isLeaderSelf) {
                        NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
                        return;
                    }
                    Set<UUID> next = new LinkedHashSet<>(selected);
                    if (next.contains(memberId)) {
                        next.remove(memberId);
                    } else if (next.size() >= BreachEngine.BREACH_MAX_SQUAD) {
                        NetworkSoundCue.UI_BUTTON_FAILURE.play(context.viewer());
                        context.viewer().sendMessage(Component.text(
                            "Squad is full (max " + BreachEngine.BREACH_MAX_SQUAD + ").", NamedTextColor.RED));
                        return;
                    } else {
                        next.add(memberId);
                    }
                    NetworkSoundCue.UI_BUTTON_CLICK.play(context.viewer());
                    this.render(guiManager, leader, snapshot, mapId, next);
                }
            );
        }

        menu.button(
            CONFIRM_SLOT,
            GuiButtonLibrary.positiveAction(Material.LIME_WOOL, "Deploy Squad", lore -> lore
                .fact("Squad size", String.valueOf(selected.size()))
                .plain("Start the breach with the selected members.")),
            context -> {
                this.selections.put(leader.getUniqueId(),
                    new Selection(new LinkedHashSet<>(selected), System.currentTimeMillis() + SELECTION_TTL_MILLIS));
                NetworkSoundCue.UI_BUTTON_CLICK.play(context.viewer());
                context.close();
                this.engine.play(context.viewer(), mapId);
            }
        );

        guiManager.open(leader, menu.build());
    }

    private void autoSelectAndPlay(Player leader, SocialGraphRepository.PartySnapshot snapshot, String mapId) {
        Set<UUID> selected = new LinkedHashSet<>();
        selected.add(leader.getUniqueId());
        for (SocialGraphRepository.PartyMember member : snapshot.members()) {
            if (selected.size() >= BreachEngine.BREACH_MAX_SQUAD) {
                break;
            }
            selected.add(member.playerId());
        }
        this.selections.put(leader.getUniqueId(),
            new Selection(selected, System.currentTimeMillis() + SELECTION_TTL_MILLIS));
        this.engine.play(leader, mapId);
    }

    private static boolean isOnlineForDisplay(UUID memberId) {
        Player online = Bukkit.getPlayer(memberId);
        return online != null && online.isOnline();
    }

    private record Selection(Set<UUID> memberIds, long expiresAt) {
    }
}
