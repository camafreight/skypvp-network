package network.skypvp.extraction.gameplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.hud.ClientTitles;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Per-raid random extract zone close times plus broadcast warnings. */
public final class BreachExtractZoneSchedule {

    private final Map<String, Integer> closeAtRemainingSeconds = new LinkedHashMap<>();
    private final Set<String> firedAlerts = ConcurrentHashMap.newKeySet();
    private final int forceCloseSeconds;
    private final int closingSoonSeconds;
    private final List<Integer> warningThresholds;

    private BreachExtractZoneSchedule(
            Map<String, Integer> closeAtRemainingSeconds,
            int forceCloseSeconds,
            int closingSoonSeconds,
            List<Integer> warningThresholds
    ) {
        this.closeAtRemainingSeconds.putAll(closeAtRemainingSeconds);
        this.forceCloseSeconds = forceCloseSeconds;
        this.closingSoonSeconds = closingSoonSeconds;
        this.warningThresholds = List.copyOf(warningThresholds);
    }

    public static BreachExtractZoneSchedule roll(
            BreachMapMeta mapMeta,
            BreachConfigService config,
            Random random
    ) {
        Objects.requireNonNull(mapMeta, "mapMeta");
        Objects.requireNonNull(config, "config");
        Random rng = random != null ? random : new Random();
        int duration = mapMeta.durationSeconds();
        int forceClose = config.extractForceCloseSeconds();
        int minOpen = Math.min(config.extractZoneMinOpenSeconds(), Math.max(0, duration - forceClose - 1));
        int latestCloseRemaining = Math.max(forceClose + 1, duration - minOpen);
        int earliestCloseRemaining = forceClose + 1;

        // Independent uniform rolls can (and did) close EVERY zone in the first third of a raid,
        // leaving 30+ minutes with no way out while matchmaking still considered the breach
        // joinable by time. Reserve a random subset of "anchor" zones that stay usable until the
        // global force close so a minimum number of extracts is always available beforehand.
        List<BreachMapMeta.ExtractZone> zones = new ArrayList<>(mapMeta.extractZones());
        Set<String> anchors = new HashSet<>();
        int anchorCount = Math.min(config.extractZoneMinOpenCount(), zones.size());
        while (anchors.size() < anchorCount) {
            anchors.add(zones.get(rng.nextInt(zones.size())).id());
        }

        Map<String, Integer> schedule = new LinkedHashMap<>();
        for (BreachMapMeta.ExtractZone zone : zones) {
            if (anchors.contains(zone.id())) {
                schedule.put(zone.id(), forceClose);
            } else if (latestCloseRemaining <= earliestCloseRemaining) {
                schedule.put(zone.id(), forceClose + 1);
            } else {
                int closeAt = earliestCloseRemaining
                        + rng.nextInt(latestCloseRemaining - earliestCloseRemaining + 1);
                schedule.put(zone.id(), closeAt);
            }
        }
        return new BreachExtractZoneSchedule(
                schedule,
                forceClose,
                config.extractClosingSoonSeconds(),
                config.extractZoneWarningSeconds()
        );
    }

    public Map<String, Integer> closeAtRemainingSeconds() {
        return Map.copyOf(closeAtRemainingSeconds);
    }

    public int forceCloseSeconds() {
        return forceCloseSeconds;
    }

    public BreachExtractZoneVisualService.ExtractAvailability zoneAvailability(
            String zoneId,
            BreachState state,
            int remainingSeconds
    ) {
        if (state != BreachState.ACTIVE && state != BreachState.TOXIC) {
            return BreachExtractZoneVisualService.ExtractAvailability.CLOSED;
        }
        if (remainingSeconds <= forceCloseSeconds) {
            return BreachExtractZoneVisualService.ExtractAvailability.CLOSED;
        }
        Integer closeAt = closeAtRemainingSeconds.get(zoneId);
        if (closeAt == null) {
            return BreachExtractZoneVisualService.ExtractAvailability.OPEN;
        }
        if (remainingSeconds <= closeAt) {
            return BreachExtractZoneVisualService.ExtractAvailability.CLOSED;
        }
        if (remainingSeconds <= closeAt + closingSoonSeconds) {
            return BreachExtractZoneVisualService.ExtractAvailability.CLOSING_SOON;
        }
        return BreachExtractZoneVisualService.ExtractAvailability.OPEN;
    }

    public boolean isZoneOpen(String zoneId, BreachState state, int remainingSeconds) {
        return zoneAvailability(zoneId, state, remainingSeconds)
                == BreachExtractZoneVisualService.ExtractAvailability.OPEN;
    }

    /** True while the zone can still be used (open or closing-soon). */
    public boolean isZoneUsable(String zoneId, BreachState state, int remainingSeconds) {
        BreachExtractZoneVisualService.ExtractAvailability availability =
                zoneAvailability(zoneId, state, remainingSeconds);
        return availability == BreachExtractZoneVisualService.ExtractAvailability.OPEN
                || availability == BreachExtractZoneVisualService.ExtractAvailability.CLOSING_SOON;
    }

    /**
     * Seconds until this zone closes at the current match clock, or {@code 0} when already closed.
     */
    public int secondsUntilClose(String zoneId, BreachState state, int remainingSeconds) {
        if (!isZoneUsable(zoneId, state, remainingSeconds)) {
            return 0;
        }
        Integer closeAt = closeAtRemainingSeconds.get(zoneId);
        int effectiveCloseAt = closeAt == null ? forceCloseSeconds : Math.max(closeAt, forceCloseSeconds);
        return Math.max(0, remainingSeconds - effectiveCloseAt);
    }

    /** Full open window for the zone from match start until its scheduled close. */
    public int totalOpenSeconds(String zoneId, int matchDurationSeconds) {
        Integer closeAt = closeAtRemainingSeconds.get(zoneId);
        int effectiveCloseAt = closeAt == null ? forceCloseSeconds : Math.max(closeAt, forceCloseSeconds);
        return Math.max(1, Math.max(0, matchDurationSeconds) - effectiveCloseAt);
    }

    public boolean isLocationInOpenZone(BreachMapMeta mapMeta, BreachState state, int remainingSeconds, LocationLike location) {
        if (location == null) {
            return false;
        }
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            if (!zone.contains(location.x(), location.y(), location.z())) {
                continue;
            }
            return isZoneOpen(zone.id(), state, remainingSeconds);
        }
        return false;
    }

    public String zoneIdAt(BreachMapMeta mapMeta, double x, double y, double z) {
        for (BreachMapMeta.ExtractZone zone : mapMeta.extractZones()) {
            if (zone.contains(x, y, z)) {
                return zone.id();
            }
        }
        return null;
    }

    public void tickAlerts(
            BreachState state,
            int previousRemaining,
            int currentRemaining,
            Iterable<Player> viewers,
            PaperCorePlugin core
    ) {
        if (state != BreachState.ACTIVE || viewers == null) {
            return;
        }
        if (previousRemaining > forceCloseSeconds && currentRemaining <= forceCloseSeconds) {
            fireOnce("global:force-close", () -> broadcastGlobalForceClose(viewers, core));
        }
        if (previousRemaining > forceCloseSeconds && currentRemaining <= forceCloseSeconds + closingSoonSeconds) {
            fireOnce("global:toxicity-creep", () -> broadcastToxicityCreep(viewers));
        }
        for (Map.Entry<String, Integer> entry : closeAtRemainingSeconds.entrySet()) {
            String zoneId = entry.getKey();
            int closeAt = entry.getValue();
            for (int threshold : warningThresholds) {
                int alertAt = closeAt + threshold;
                if (previousRemaining > alertAt && currentRemaining <= alertAt) {
                    fireOnce(zoneId + ":warn:" + threshold, () -> broadcastZoneWarning(viewers, zoneId, threshold, core));
                }
            }
            if (previousRemaining > closeAt && currentRemaining <= closeAt) {
                fireOnce(zoneId + ":closed", () -> broadcastZoneClosed(viewers, zoneId, core));
            }
        }
    }

    public void broadcastToxicPhaseStart(Iterable<Player> viewers, PaperCorePlugin core) {
        for (Player player : viewers) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            ClientTitles.offer(
                    core,
                    player,
                    ExtractionTexts.miniMessage(player, "extraction.title.toxic_phase"),
                    ExtractionTexts.miniMessage(player, "extraction.title.toxic_phase_subtitle"),
                    5,
                    60,
                    10
            );
            ExtractFeedback.allZonesForceClosed(player);
            player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.toxic.phase_started"));
        }
    }

    private void broadcastGlobalForceClose(Iterable<Player> viewers, PaperCorePlugin core) {
        for (Player player : viewers) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            ClientTitles.offer(
                    core,
                    player,
                    ExtractionTexts.miniMessage(player, "extraction.title.all_extracts_closing"),
                    ExtractionTexts.miniMessage(player, "extraction.title.all_extracts_closing_subtitle"),
                    5,
                    60,
                    10
            );
            ExtractFeedback.allZonesForceClosed(player);
            player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.extract.all_zones_force_closed"));
        }
    }

    private void broadcastToxicityCreep(Iterable<Player> viewers) {
        for (Player player : viewers) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.toxic.creep_started"));
        }
    }

    private void broadcastZoneWarning(Iterable<Player> viewers, String zoneId, int secondsRemaining, PaperCorePlugin core) {
        String label = formatZoneLabel(zoneId);
        for (Player player : viewers) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (secondsRemaining <= 5) {
                ClientTitles.offer(
                        core,
                        player,
                        Component.text(Integer.toString(secondsRemaining), NamedTextColor.RED),
                        ExtractionTexts.miniMessage(player, "extraction.title.zone_closing_subtitle", label),
                        0,
                        20,
                        5,
                        network.skypvp.paper.clientupdate.ClientUpdatePipeline.PRIORITY_FLASH
                );
                ExtractFeedback.zoneClosingSoon(player, secondsRemaining);
            } else if (secondsRemaining == 10 || secondsRemaining == 30 || secondsRemaining == 60) {
                ClientTitles.offer(
                        core,
                        player,
                        ExtractionTexts.miniMessage(player, "extraction.title.zone_closing", label),
                        ExtractionTexts.miniMessage(
                                player,
                                "extraction.title.zone_closing_countdown",
                                secondsRemaining
                        ),
                        5,
                        40,
                        5
                );
                ExtractFeedback.zoneClosingSoon(player, secondsRemaining);
            }
            player.sendMessage(ExtractionTexts.miniMessage(
                    player,
                    "extraction.extract.zone.closing_countdown",
                    label,
                    secondsRemaining
            ));
        }
    }

    private void broadcastZoneClosed(Iterable<Player> viewers, String zoneId, PaperCorePlugin core) {
        String label = formatZoneLabel(zoneId);
        for (Player player : viewers) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            ClientTitles.offer(
                    core,
                    player,
                    ExtractionTexts.miniMessage(player, "extraction.title.zone_closed", label),
                    ExtractionTexts.miniMessage(player, "extraction.title.zone_closed_subtitle", label),
                    5,
                    40,
                    10
            );
            ExtractFeedback.zoneClosed(player);
            player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.extract.zone.closed_final", label));
        }
    }

    private void fireOnce(String key, Runnable action) {
        if (firedAlerts.add(key)) {
            action.run();
        }
    }

    private static String formatZoneLabel(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return "Extract";
        }
        return zoneId.replace('-', ' ');
    }

    public record LocationLike(double x, double y, double z) {
    }
}
