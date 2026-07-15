package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Scopes the vanilla tab list to hub players or players sharing the same breach instance.
 */
public final class BreachTabVisibilityService {

    private final PaperCorePlugin core;
    private final ServerPlatform scheduler;
    private final BreachEngine engine;
    private final BreachSpectatorService spectatorService;

    public BreachTabVisibilityService(
            PaperCorePlugin core,
            ServerPlatform scheduler,
            BreachEngine engine,
            BreachSpectatorService spectatorService
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.spectatorService = Objects.requireNonNull(spectatorService, "spectatorService");
    }

    public void reconcileAll() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            reconcile(viewer, online);
        }
    }

    public void reconcile(Player viewer) {
        reconcile(viewer, new ArrayList<>(Bukkit.getOnlinePlayers()));
    }

    private void reconcile(Player viewer, List<Player> online) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        Optional<BreachInstance> viewerInstance = engine.instanceFor(viewer);
        scheduler.runOnPlayer(viewer, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            for (Player other : online) {
                if (other == null || !other.isOnline() || viewer.getUniqueId().equals(other.getUniqueId())) {
                    continue;
                }
                Optional<BreachInstance> otherInstance = engine.instanceFor(other);
                if (shouldShowInTab(viewer, other, viewerInstance, otherInstance)) {
                    viewer.showPlayer(core, other);
                } else {
                    viewer.hidePlayer(core, other);
                }
            }
            // showPlayer re-lists real TAB rows and drops per-viewer nametag-hide teams on the client.
            // Re-apply both immediately, then once more after the client finishes entity spawn.
            restoreTabBoardAndNametagHide(viewer);
            scheduler.runOnPlayerLater(viewer, () -> restoreTabBoardAndNametagHide(viewer), 2L);
        });
    }

    private void restoreTabBoardAndNametagHide(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        if (core.tabBoardService() != null) {
            core.tabBoardService().rehideRealPlayersIfBoardActive(viewer);
        }
        if (core.nametagLibrary() != null) {
            core.nametagLibrary().resyncViewer(viewer);
        }
    }

    private boolean shouldShowInTab(
            Player viewer,
            Player other,
            Optional<BreachInstance> viewerInstance,
            Optional<BreachInstance> otherInstance
    ) {
        boolean viewerSpectating = spectatorService.isSpectating(viewer);
        boolean otherSpectating = spectatorService.isSpectating(other);
        // Live raiders must not see eliminated ghosts (TAB + entity). Spectators must still see live raiders
        // in their breach so they can watch the raid — only block ghost visibility for active players.
        if (otherSpectating && !viewerSpectating) {
            return false;
        }

        String viewerKey = viewerInstance.map(BreachInstance::instanceId).orElse(null);
        String otherKey = otherInstance.map(BreachInstance::instanceId).orElse(null);
        if (viewerKey == null && otherKey == null) {
            return true;
        }
        if (viewerKey == null || otherKey == null) {
            return false;
        }
        return viewerKey.equals(otherKey);
    }
}
