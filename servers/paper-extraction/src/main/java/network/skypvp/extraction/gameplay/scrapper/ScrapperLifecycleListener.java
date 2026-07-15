package network.skypvp.extraction.gameplay.scrapper;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;

/** Resets scrapper per-raid progress when a raid session ends. Buffered materials persist for hub collection. */
public final class ScrapperLifecycleListener {

    private ScrapperLifecycleListener() {
    }

    public static void bind(BreachEngine engine, ScrapperService scrapperService) {
        bind(engine, scrapperService, null);
    }

    public static void bind(BreachEngine engine, ScrapperService scrapperService, PaperCorePlugin core) {
        if (engine == null || scrapperService == null || engine.gameplayCoordinator() == null) {
            return;
        }
        engine.gameplayCoordinator().bindRaidSessionListener(new RaidSessionListenerAdapter(scrapperService, core));
    }

    private static final class RaidSessionListenerAdapter implements network.skypvp.extraction.gameplay.RaidSessionListener {
        private final ScrapperService scrapperService;
        private final PaperCorePlugin core;

        private RaidSessionListenerAdapter(ScrapperService scrapperService, PaperCorePlugin core) {
            this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
            this.core = core;
        }

        @Override
        public void onPlayerRaidSessionStarted(Player player) {
            scrapperService.warmPlayer(player);
        }

        @Override
        public void onPlayerRaidSessionEnded(Player player) {
            scrapperService.resetRaidProgress(player);
            // Hub return may not fire a world-change if already on the pod — refresh pending CTAs.
            if (core != null && core.questSignals() != null && player != null) {
                core.questSignals().refresh(player);
            }
        }
    }
}
