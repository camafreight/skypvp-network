package network.skypvp.extraction.gameplay;

import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class BreachCombatFeedback {

    private BreachCombatFeedback() {
    }

    public static void showToxicEliminated(Player victim, String killerName, PaperCorePlugin core) {
        Objects.requireNonNull(victim, "victim");
        victim.playSound(victim.getLocation(), Sound.ENTITY_HUSK_DEATH, 0.75F, 0.85F);
        Component title = ExtractionTexts.miniMessage(victim, "extraction.toxic.death_title");
        Component subtitle = ExtractionTexts.miniMessage(victim, "extraction.toxic.death_subtitle");
        network.skypvp.extraction.hud.ClientTitles.offer(core, victim, title, subtitle, 5, 40, 10);
    }

    public static void showEliminated(Player victim, String killerName, PaperCorePlugin core) {
        Objects.requireNonNull(victim, "victim");
        victim.playSound(victim.getLocation(), Sound.ENTITY_WITHER_HURT, 0.55F, 1.15F);
        Component title = ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_title");
        Component subtitle = killerName == null || killerName.isBlank()
                ? ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_subtitle_solo")
                : ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_subtitle_killer", killerName);
        network.skypvp.extraction.hud.ClientTitles.offer(core, victim, title, subtitle, 5, 40, 10);
    }

    public static void broadcastElimination(BreachInstance instance, Player victim, String killerName) {
        if (instance == null || victim == null) {
            return;
        }
        broadcastElimination(instance, victim.getUniqueId(), victim.getName(), killerName);
    }

    /**
     * Name-based elimination broadcast for a victim who is NOT online (e.g. a disconnected raider whose killable
     * stand-in was destroyed). Mirrors the {@link Player} overload but resolves viewers via {@link org.bukkit.Bukkit}.
     */
    public static void broadcastElimination(BreachInstance instance, UUID victimId, String victimName, String killerName) {
        if (instance == null || victimId == null) {
            return;
        }
        for (UUID participantId : instance.participantsSnapshot()) {
            if (participantId.equals(victimId)) {
                continue;
            }
            Player viewer = org.bukkit.Bukkit.getPlayer(participantId);
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            Component message = killerName == null || killerName.isBlank()
                    ? ExtractionTexts.miniMessage(viewer, "extraction.combat.feedback.death_broadcast_solo", victimName)
                    : ExtractionTexts.miniMessage(
                            viewer,
                            "extraction.combat.feedback.death_broadcast_killer",
                            victimName,
                            killerName
                    );
            viewer.sendMessage(message);
        }
    }

    /**
     * Notifies squad-mates in the same breach instance when a disconnected raider's AFK body is killed or their
     * reconnect grace expires. Uses the instance's deployed-party tracking (same squad that entered together).
     */
    public static void notifyPartyDisconnectedEliminated(
            BreachInstance instance,
            UUID ownerId,
            String ownerName,
            String killerName,
            boolean graceExpired
    ) {
        if (instance == null || ownerId == null) {
            return;
        }
        UUID partyId = instance.partyIdFor(ownerId);
        if (partyId == null) {
            return;
        }
        String safeName = ownerName == null || ownerName.isBlank() ? "Raider" : ownerName;
        for (UUID memberId : instance.partyMembers(partyId)) {
            if (memberId.equals(ownerId)) {
                continue;
            }
            Player member = org.bukkit.Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline()) {
                continue;
            }
            Component message;
            if (graceExpired) {
                message = ExtractionTexts.miniMessage(member, "extraction.breach.party.disconnected_grace_expired", safeName);
            } else if (killerName == null || killerName.isBlank()) {
                message = ExtractionTexts.miniMessage(member, "extraction.breach.party.disconnected_body_eliminated_solo", safeName);
            } else {
                message = ExtractionTexts.miniMessage(
                        member,
                        "extraction.breach.party.disconnected_body_eliminated_killer",
                        safeName,
                        killerName
                );
            }
            member.sendMessage(message);
        }
    }
}
