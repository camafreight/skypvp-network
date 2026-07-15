package network.skypvp.extraction.questdialogue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.gameplay.scrapper.ScrapperService;
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
 * Static main-quest signal for the Scrap Technician ({@code scrapper_main_dialogue_quest}).
 *
 * <p>The scrapper's "main job" is passive salvage: whenever the player has materials buffered
 * from raiding, entering the extraction hub world triggers a chat shout from the technician and
 * an octagon navigator marker over the {@code hub_scrapper} NPC. Collecting the salvage (or
 * walking up to the NPC) clears the waypoint.
 *
 * <p>Look & feel are all navigator-API values ({@link #SIGNAL_COLOR}, {@link #MARKER_ICON},
 * {@link WaypointMarker#octagon}) so other quests can register their own providers with a
 * different color/icon/model without touching the renderer.
 */
public final class ScrapperQuestSignalProvider implements QuestSignalProvider {

    public static final String QUEST_ID = "scrapper_main_dialogue_quest";
    /** Extraction pod spawn world — signals deliver when the player joins/enters it. */
    private static final String HUB_WORLD = "world";
    private static final String SCRAPPER_NPC_ID = "hub_scrapper";
    private static final Color SIGNAL_COLOR = Color.fromRGB(255, 200, 60);
    /** Unicode glyph centered on the octagon plate (MiniMessage; swap per quest via the API). */
    private static final String MARKER_ICON = "<white>⚒</white>";
    private static final double ARRIVE_CLEAR_BLOCKS = 0.0D;

    private final PaperCorePlugin core;
    private final ScrapperService scrapperService;

    public ScrapperQuestSignalProvider(PaperCorePlugin core, ScrapperService scrapperService) {
        this.core = Objects.requireNonNull(core, "core");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
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
        if (!scrapperService.hasBufferedMaterials(player)) {
            return Optional.empty();
        }
        Optional<Location> anchor = core.npcLibrary() != null
                ? core.npcLibrary().findNpcAnchor(SCRAPPER_NPC_ID)
                : Optional.empty();
        if (anchor.isEmpty()
                || anchor.get().getWorld() == null
                || !HUB_WORLD.equalsIgnoreCase(anchor.get().getWorld().getName())) {
            return Optional.empty();
        }
        int buffered = scrapperService.sessionBuffered(player);
        Waypoint waypoint = Waypoint.of(
                QuestSignalService.waypointIdFor(QUEST_ID),
                anchor.get(),
                "<gray>Scrap Technician</gray>",
                SIGNAL_COLOR,
                ARRIVE_CLEAR_BLOCKS
        ).withMarker(WaypointMarker.octagon(SIGNAL_COLOR, MARKER_ICON));
        String chat = "<gray>[<white>Scrap Technician</white>]</gray> <yellow>Raider! Your scrapper stripped down "
                + "<white>" + buffered + " material" + (buffered == 1 ? "" : "s") + "</white> — "
                + "follow the marker and collect your salvage.</yellow>";
        return Optional.of(new QuestSignalDelivery(
                chat,
                waypoint,
                "Scrap Technician",
                List.of(
                        "Raider — over here!",
                        buffered + " materials ready.",
                        "Collect your salvage."
                )
        ));
    }
}
