package network.skypvp.extraction.questdialogue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.questsignal.QuestSignalDelivery;
import network.skypvp.paper.questsignal.QuestSignalProvider;
import network.skypvp.paper.questsignal.QuestSignalService;
import network.skypvp.paper.waypoint.Waypoint;
import network.skypvp.paper.waypoint.WaypointMarker;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Hub-join signal for {@code stranded_pilot}: accepted quest without a turn-in yet
 * → chat CTA + shout board + waypoint to {@code hub_pilot}.
 */
public final class PilotQuestSignalProvider implements QuestSignalProvider {

    public static final String QUEST_ID = "stranded_pilot";
    private static final String HUB_WORLD = "world";
    private static final String PILOT_NPC_ID = "hub_pilot";
    private static final Color SIGNAL_COLOR = Color.fromRGB(80, 200, 255);
    private static final String MARKER_ICON = "<aqua>✈</aqua>";
    private static final double ARRIVE_CLEAR_BLOCKS = 0.0D;

    private final PaperCorePlugin core;
    private final QuestProgressRepository progress;

    public PilotQuestSignalProvider(PaperCorePlugin core, QuestProgressRepository progress) {
        this.core = Objects.requireNonNull(core, "core");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    @Override
    public String questId() {
        return QUEST_ID;
    }

    @Override
    public String worldName() {
        return HUB_WORLD;
    }

    @Override
    public Optional<QuestSignalDelivery> evaluate(Player player) {
        if (!progress.isStage(player.getUniqueId(), QUEST_ID, QuestProgressRepository.STAGE_ACCEPTED)) {
            return Optional.empty();
        }
        if (ExtractionCustomItemProvider.hasFlightRecorder(core.customItemService(), player)) {
            // Still nudge them to turn in at the pilot.
            return delivery(
                    player,
                    "<gray>[<aqua>Stranded Pilot</aqua>]</gray> <yellow>You found my recorder — "
                            + "bring it back to me at the hub.</yellow>",
                    List.of("You found it?", "Bring the recorder here.", "I'll make it worth your while.")
            );
        }
        return delivery(
                player,
                "<gray>[<aqua>Stranded Pilot</aqua>]</gray> <yellow>Still hunting my flight recorder? "
                        + "Follow the marker when you're ready to talk.</yellow>",
                List.of("Raider — over here.", "My recorder is still out there.", "Talk to me if you need the brief.")
        );
    }

    private Optional<QuestSignalDelivery> delivery(Player player, String chat, List<String> shoutLines) {
        Optional<Location> anchor = core.npcLibrary() != null
                ? core.npcLibrary().findNpcAnchor(PILOT_NPC_ID)
                : Optional.empty();
        if (anchor.isEmpty()
                || anchor.get().getWorld() == null
                || !HUB_WORLD.equalsIgnoreCase(anchor.get().getWorld().getName())) {
            return Optional.empty();
        }
        Waypoint waypoint = Waypoint.of(
                QuestSignalService.waypointIdFor(QUEST_ID),
                anchor.get(),
                "<aqua>Stranded Pilot</aqua>",
                SIGNAL_COLOR,
                ARRIVE_CLEAR_BLOCKS
        ).withMarker(WaypointMarker.octagon(SIGNAL_COLOR, MARKER_ICON));
        return Optional.of(new QuestSignalDelivery(chat, waypoint, "Stranded Pilot", shoutLines));
    }
}
