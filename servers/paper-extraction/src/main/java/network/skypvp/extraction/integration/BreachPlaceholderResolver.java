package network.skypvp.extraction.integration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.engine.BreachQueueService;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.loot.BreachLootChestRegistry;
import network.skypvp.extraction.model.BreachState;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BreachPlaceholderResolver {

    private static final List<String> MAP_SUFFIXES = List.of(
            "active_players",
            "participants",
            "max_players",
            "time_remaining",
            "time_formatted",
            "time_seconds",
            "duration_seconds",
            "elapsed_seconds",
            "loot_percent",
            "loot_percent_exact",
            "loot_state",
            "loot_tag",
            "loot_chests_total",
            "loot_chests_remaining",
            "phase",
            "phase_tag",
            "state",
            "state_tag",
            "instances",
            "joinable",
            "map_display",
            "display_name",
            "map_id"
    );

    private final BreachEngine engine;
    private final BreachConfigService config;

    public BreachPlaceholderResolver(BreachEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = engine.configService();
    }

    public String resolve(OfflinePlayer offlinePlayer, String params) {
        String key = params == null ? "" : params.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }

        String global = resolveGlobal(key);
        if (global != null) {
            return global;
        }

        if (key.startsWith("youngest_")) {
            return resolveYoungest(key.substring("youngest_".length()));
        }

        String mapScoped = resolveMapScoped(key);
        if (mapScoped != null) {
            return mapScoped;
        }

        Player player = offlinePlayer != null && offlinePlayer.isOnline() ? offlinePlayer.getPlayer() : null;
        if (player != null) {
            return resolvePlayer(player, key);
        }
        return "";
    }

    private String resolveGlobal(String key) {
        return switch (key) {
            case "active_instances", "active_total", "active_breaches" ->
                    Integer.toString(engine.activeInstances().size());
            case "total_active_players", "network_active_players" ->
                    Integer.toString(sumActivePlayers(engine.activeInstances()));
            case "total_participants", "network_participants" ->
                    Integer.toString(sumParticipants(engine.activeInstances()));
            case "queue_size", "queued_players" ->
                    Integer.toString(engine.queueService().size());
            case "capacity_remaining", "open_breach_slots" ->
                    Integer.toString(engine.worldPool().capacityRemaining());
            case "max_breaches_per_pod" ->
                    Integer.toString(config.maxBreachesPerPod());
            case "active_maps", "active_map_list" -> formatActiveMapList();
            default -> null;
        };
    }

    private String resolveYoungest(String suffix) {
        Optional<BreachInstance> youngest = selectYoungestActiveInstance();
        if (youngest.isEmpty()) {
            return defaultYoungest(suffix);
        }
        return resolveInstanceScoped(youngest.get(), suffix, true);
    }

    private String resolveMapScoped(String key) {
        for (String suffix : MAP_SUFFIXES) {
            String needle = "_" + suffix;
            if (!key.endsWith(needle)) {
                continue;
            }
            String mapId = key.substring(0, key.length() - needle.length());
            if (mapId.isBlank()) {
                return "";
            }
            return resolveMapAggregate(mapId, suffix);
        }
        return null;
    }

    private String resolveMapAggregate(String mapId, String suffix) {
        List<BreachInstance> instances = instancesForMap(mapId);
        if (instances.isEmpty()) {
            return defaultMapAggregate(suffix);
        }

        return switch (suffix) {
            case "active_players" -> Integer.toString(sumActivePlayers(instances));
            case "participants" -> Integer.toString(sumParticipants(instances));
            case "max_players" -> Integer.toString(instances.get(0).mapMeta().maxPlayers());
            case "instances" -> Integer.toString(instances.size());
            case "joinable" -> instances.stream().anyMatch(BreachInstance::canAcceptPlayers) ? "yes" : "no";
            case "map_display", "display_name" -> instances.get(0).mapMeta().displayName();
            case "map_id" -> instances.get(0).mapMeta().mapId();
            case "time_remaining" -> Integer.toString(instances.stream()
                    .mapToInt(instance -> Math.max(0, instance.remainingSeconds()))
                    .max()
                    .orElse(0));
            case "time_formatted" -> BreachPlaceholderTags.formatDuration(instances.stream()
                    .mapToInt(instance -> Math.max(0, instance.remainingSeconds()))
                    .max()
                    .orElse(0));
            case "time_seconds" -> resolveMapAggregate(mapId, "time_remaining");
            case "duration_seconds" -> Integer.toString(instances.get(0).durationSeconds());
            case "elapsed_seconds" -> Integer.toString(instances.stream()
                    .mapToInt(BreachInstance::elapsedActiveSeconds)
                    .min()
                    .orElse(0));
            case "loot_percent" -> BreachPlaceholderTags.formatPercent(aggregateLootPercent(instances));
            case "loot_percent_exact" -> BreachPlaceholderTags.formatPercentDetailed(aggregateLootPercent(instances));
            case "loot_state" -> BreachPlaceholderTags.lootStateKey(aggregateLootPercent(instances));
            case "loot_tag" -> BreachPlaceholderTags.lootStateLabel(aggregateLootPercent(instances));
            case "loot_chests_total" -> Integer.toString(aggregateLootStats(instances).registeredChests());
            case "loot_chests_remaining" -> Integer.toString(aggregateLootStats(instances).chestsWithLoot());
            case "phase" -> BreachPlaceholderTags.phaseKey(selectYoungestForMap(instances).orElse(instances.get(0)), config);
            case "phase_tag" -> BreachPlaceholderTags.phaseLabel(selectYoungestForMap(instances).orElse(instances.get(0)), config);
            case "state" -> instances.stream()
                    .map(instance -> instance.state().name().toLowerCase(Locale.ROOT))
                    .distinct()
                    .collect(Collectors.joining(","));
            case "state_tag" -> instances.stream()
                    .map(instance -> BreachPlaceholderTags.phaseLabel(instance, config))
                    .distinct()
                    .collect(Collectors.joining(", "));
            default -> "";
        };
    }

    private String resolvePlayer(Player player, String key) {
        Optional<BreachQueueService.QueuedPlayer> queued = engine.queueService().find(player.getUniqueId());
        if (queued.isPresent()) {
            return resolveQueuedPlayer(player, key, queued.get());
        }

        Optional<BreachInstance> instanceOptional = engine.instanceFor(player);
        if (instanceOptional.isEmpty()) {
            return resolveIdlePlayer(player, key);
        }

        return resolveInstancePlayer(player, instanceOptional.get(), key);
    }

    private String resolveQueuedPlayer(Player player, String key, BreachQueueService.QueuedPlayer queued) {
        return switch (key) {
            case "state", "state_tag" -> "queued";
            case "map", "map_id" -> queued.mapId();
            case "map_display" -> config.mapMeta(queued.mapId())
                    .map(meta -> meta.displayName())
                    .orElse(queued.mapId());
            case "queue_position" -> {
                int position = engine.queueService().position(player.getUniqueId());
                yield position > 0 ? Integer.toString(position) : "";
            }
            case "queue_size" -> Integer.toString(engine.queueService().size());
            default -> "";
        };
    }

    private String resolveIdlePlayer(Player player, String key) {
        return switch (key) {
            case "state" -> "idle";
            case "state_tag" -> "Idle";
            case "phase" -> "idle";
            case "phase_tag" -> "Idle";
            case "map", "map_id", "map_display" -> "";
            case "players", "active_players" -> "0";
            case "max_players" -> "0";
            case "time_remaining", "time_seconds" -> "0";
            case "time_formatted" -> BreachPlaceholderTags.formatDuration(0);
            case "loot_percent", "loot_percent_exact" -> "0";
            case "loot_state" -> "unknown";
            case "loot_tag" -> "Unknown";
            case "queue_position" -> {
                int position = engine.queueService().position(player.getUniqueId());
                yield position > 0 ? Integer.toString(position) : "";
            }
            default -> "";
        };
    }

    private String resolveInstancePlayer(Player player, BreachInstance instance, String key) {
        BreachLootChestRegistry.WorldLootStats lootStats = lootStats(instance);
        double lootPercent = BreachPlaceholderTags.lootPercent(lootStats);

        return switch (key) {
            case "state" -> instance.state().name().toLowerCase(Locale.ROOT);
            case "state_tag" -> BreachPlaceholderTags.phaseLabel(instance, config);
            case "phase" -> BreachPlaceholderTags.phaseKey(instance, config);
            case "phase_tag" -> BreachPlaceholderTags.phaseLabel(instance, config);
            case "map", "map_id" -> instance.mapMeta().mapId();
            case "map_display" -> instance.mapMeta().displayName();
            case "instance_id" -> instance.instanceId();
            case "players", "active_players" -> Integer.toString(instance.activeParticipantCount());
            case "players_in_world" -> Integer.toString(instance.playersInBreachWorld());
            case "participants" -> Integer.toString(instance.participantCount());
            case "max_players" -> Integer.toString(instance.mapMeta().maxPlayers());
            case "extracted_count" -> Integer.toString(instance.extractedCount());
            case "eliminated_count" -> Integer.toString(instance.eliminatedCount());
            case "pending_joins" -> Integer.toString(instance.pendingJoinCount());
            case "join_countdown" -> Integer.toString(instance.joinCountdownSeconds(player.getUniqueId()));
            case "time_remaining", "time_seconds" -> Integer.toString(Math.max(0, instance.remainingSeconds()));
            case "time_formatted" -> instance.formattedRemainingTime();
            case "duration_seconds" -> Integer.toString(instance.durationSeconds());
            case "elapsed_seconds" -> Integer.toString(instance.elapsedActiveSeconds());
            case "time_progress_percent" -> Integer.toString(BreachPlaceholderTags.timeProgressPercent(instance));
            case "time_remaining_percent" -> Integer.toString(BreachPlaceholderTags.timeRemainingPercent(instance));
            case "loot_percent" -> BreachPlaceholderTags.formatPercent(lootPercent);
            case "loot_percent_exact" -> BreachPlaceholderTags.formatPercentDetailed(lootPercent);
            case "loot_state" -> BreachPlaceholderTags.lootStateKey(lootPercent);
            case "loot_tag" -> BreachPlaceholderTags.lootStateLabel(lootPercent);
            case "loot_chests_total" -> Integer.toString(lootStats.registeredChests());
            case "loot_chests_remaining" -> Integer.toString(lootStats.chestsWithLoot());
            case "joinable" -> instance.canAcceptPlayers() ? "yes" : "no";
            case "extracted" -> instance.hasExtracted(player.getUniqueId()) ? "yes" : "no";
            case "eliminated" -> instance.isEliminated(player.getUniqueId()) ? "yes" : "no";
            case "queue_position" -> "";
            default -> "";
        };
    }

    private String resolveInstanceScoped(BreachInstance instance, String suffix, boolean includeMapAliases) {
        BreachLootChestRegistry.WorldLootStats lootStats = lootStats(instance);
        double lootPercent = BreachPlaceholderTags.lootPercent(lootStats);

        return switch (suffix) {
            case "map", "map_id" -> instance.mapMeta().mapId();
            case "map_display", "name" -> instance.mapMeta().displayName();
            case "instance_id" -> instance.instanceId();
            case "active_players", "players" -> Integer.toString(instance.activeParticipantCount());
            case "players_in_world" -> Integer.toString(instance.playersInBreachWorld());
            case "participants" -> Integer.toString(instance.participantCount());
            case "max_players" -> Integer.toString(instance.mapMeta().maxPlayers());
            case "time_remaining", "time_seconds" -> Integer.toString(Math.max(0, instance.remainingSeconds()));
            case "time_formatted" -> instance.formattedRemainingTime();
            case "duration_seconds" -> Integer.toString(instance.durationSeconds());
            case "elapsed_seconds" -> Integer.toString(instance.elapsedActiveSeconds());
            case "time_progress_percent" -> Integer.toString(BreachPlaceholderTags.timeProgressPercent(instance));
            case "time_remaining_percent" -> Integer.toString(BreachPlaceholderTags.timeRemainingPercent(instance));
            case "loot_percent" -> BreachPlaceholderTags.formatPercent(lootPercent);
            case "loot_percent_exact" -> BreachPlaceholderTags.formatPercentDetailed(lootPercent);
            case "loot_state" -> BreachPlaceholderTags.lootStateKey(lootPercent);
            case "loot_tag" -> BreachPlaceholderTags.lootStateLabel(lootPercent);
            case "loot_chests_total" -> Integer.toString(lootStats.registeredChests());
            case "loot_chests_remaining" -> Integer.toString(lootStats.chestsWithLoot());
            case "phase" -> BreachPlaceholderTags.phaseKey(instance, config);
            case "phase_tag" -> BreachPlaceholderTags.phaseLabel(instance, config);
            case "state" -> instance.state().name().toLowerCase(Locale.ROOT);
            case "state_tag" -> BreachPlaceholderTags.phaseLabel(instance, config);
            case "joinable" -> instance.canAcceptPlayers() ? "yes" : "no";
            case "play_command" -> "/breach play " + instance.mapMeta().mapId();
            default -> includeMapAliases ? "" : "";
        };
    }

    private String defaultYoungest(String suffix) {
        return switch (suffix) {
            case "map", "map_id", "map_display", "name", "instance_id", "play_command" -> "";
            case "joinable" -> "no";
            case "loot_state" -> "unknown";
            case "loot_tag", "state_tag", "phase_tag" -> "None";
            case "phase", "state" -> "none";
            default -> "0";
        };
    }

    private String defaultMapAggregate(String suffix) {
        return switch (suffix) {
            case "map_display", "display_name", "map_id" -> "";
            case "joinable" -> "no";
            case "loot_state" -> "unknown";
            case "loot_tag", "state_tag", "phase_tag" -> "None";
            case "phase", "state" -> "none";
            default -> "0";
        };
    }

    private Optional<BreachInstance> selectYoungestActiveInstance() {
        return engine.activeInstances().stream()
                .filter(instance -> instance.state() == BreachState.ACTIVE)
                .max(Comparator.comparingInt(BreachInstance::remainingSeconds)
                        .thenComparingInt(instance -> -instance.elapsedActiveSeconds()));
    }

    private Optional<BreachInstance> selectYoungestForMap(List<BreachInstance> instances) {
        return instances.stream()
                .filter(instance -> instance.state() == BreachState.ACTIVE)
                .max(Comparator.comparingInt(BreachInstance::remainingSeconds)
                        .thenComparingInt(instance -> -instance.elapsedActiveSeconds()));
    }

    private List<BreachInstance> instancesForMap(String mapId) {
        String normalized = mapId.toLowerCase(Locale.ROOT);
        List<BreachInstance> matches = new ArrayList<>();
        for (BreachInstance instance : engine.activeInstances()) {
            if (instance.mapMeta().mapId().equalsIgnoreCase(normalized)) {
                matches.add(instance);
            }
        }
        return matches;
    }

    private String formatActiveMapList() {
        return engine.activeInstances().stream()
                .map(instance -> instance.mapMeta().mapId())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static int sumActivePlayers(List<BreachInstance> instances) {
        int total = 0;
        for (BreachInstance instance : instances) {
            total += instance.activeParticipantCount();
        }
        return total;
    }

    private static int sumParticipants(List<BreachInstance> instances) {
        int total = 0;
        for (BreachInstance instance : instances) {
            total += instance.participantCount();
        }
        return total;
    }

    private double aggregateLootPercent(List<BreachInstance> instances) {
        BreachLootChestRegistry.WorldLootStats stats = aggregateLootStats(instances);
        return BreachPlaceholderTags.lootPercent(stats);
    }

    private BreachLootChestRegistry.WorldLootStats aggregateLootStats(List<BreachInstance> instances) {
        int registeredChests = 0;
        int chestsWithLoot = 0;
        int itemSlotsTotal = 0;
        int itemSlotsRemaining = 0;
        for (BreachInstance instance : instances) {
            BreachLootChestRegistry.WorldLootStats stats = lootStats(instance);
            registeredChests += stats.registeredChests();
            chestsWithLoot += stats.chestsWithLoot();
            itemSlotsTotal += stats.itemSlotsTotal();
            itemSlotsRemaining += stats.itemSlotsRemaining();
        }
        double percentRemaining = itemSlotsTotal <= 0
                ? 0.0D
                : (100.0D * itemSlotsRemaining) / itemSlotsTotal;
        return new BreachLootChestRegistry.WorldLootStats(
                registeredChests,
                chestsWithLoot,
                itemSlotsTotal,
                itemSlotsRemaining,
                percentRemaining
        );
    }

    private BreachLootChestRegistry.WorldLootStats lootStats(BreachInstance instance) {
        BreachGameplayCoordinator coordinator = engine.gameplayCoordinator();
        World world = instance.world();
        if (coordinator == null || world == null) {
            return BreachLootChestRegistry.WorldLootStats.empty();
        }
        return coordinator.lootService().chestRegistry().aggregateStatsForWorld(world);
    }
}
