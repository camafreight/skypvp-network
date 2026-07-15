package network.skypvp.extraction.questdialogue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.database.AsyncDbExecutor;

/**
 * Per-player quest stage store ({@code accepted}, {@code completed}, …) for extraction quests.
 * Backed by {@code extraction_quest_progress} with an in-memory read-through cache.
 */
public final class QuestProgressRepository {

    public static final String STAGE_ACCEPTED = "accepted";
    public static final String STAGE_REFUSED = "refused";
    public static final String STAGE_COMPLETED = "completed";

    private final AsyncDbExecutor asyncDbExecutor;
    private final Map<UUID, Map<String, String>> cache = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public QuestProgressRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Void> ensureSchema() {
        return asyncDbExecutor.method_244("questProgress.ensureSchema", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS extraction_quest_progress (
                        player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
                        quest_id VARCHAR(64) NOT NULL,
                        stage VARCHAR(64) NOT NULL,
                        payload TEXT,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (player_uuid, quest_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE INDEX IF NOT EXISTS idx_extraction_quest_progress_player
                        ON extraction_quest_progress (player_uuid)
                    """
            )) {
                ps.executeUpdate();
            }
            ready = true;
        });
    }

    public Optional<String> stageOf(UUID playerId, String questId) {
        if (playerId == null || questId == null || questId.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(questId);
        Map<String, String> player = cache.get(playerId);
        if (player != null && player.containsKey(key)) {
            return Optional.ofNullable(player.get(key));
        }
        if (!ready) {
            return Optional.empty();
        }
        try {
            Optional<String> loaded = asyncDbExecutor.supply("questProgress.load", connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        """
                        SELECT stage FROM extraction_quest_progress
                        WHERE player_uuid = ? AND quest_id = ?
                        """
                )) {
                    ps.setObject(1, playerId);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return Optional.ofNullable(rs.getString("stage"));
                        }
                    }
                }
                return Optional.<String>empty();
            }).join();
            loaded.ifPresent(stage -> cache.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(key, stage));
            return loaded;
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public boolean isStage(UUID playerId, String questId, String stage) {
        return stageOf(playerId, questId).filter(s -> s.equalsIgnoreCase(stage)).isPresent();
    }

    public void setStage(UUID playerId, String questId, String stage) {
        if (playerId == null || questId == null || stage == null || stage.isBlank()) {
            return;
        }
        String key = normalize(questId);
        String normalizedStage = stage.trim().toLowerCase(Locale.ROOT);
        cache.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).put(key, normalizedStage);
        if (!ready) {
            return;
        }
        asyncDbExecutor.method_244("questProgress.upsert", connection -> {
            try (PreparedStatement profile = connection.prepareStatement(
                    "INSERT INTO extraction_player_profiles (player_uuid, revision, updated_at) "
                            + "VALUES (?, 0, NOW()) ON CONFLICT (player_uuid) DO NOTHING"
            )) {
                profile.setObject(1, playerId);
                profile.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO extraction_quest_progress (player_uuid, quest_id, stage, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON CONFLICT (player_uuid, quest_id) DO UPDATE
                    SET stage = EXCLUDED.stage, updated_at = NOW()
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, key);
                ps.setString(3, normalizedStage);
                ps.executeUpdate();
            }
        }).exceptionally(error -> null);
    }

    public void clear(UUID playerId, String questId) {
        if (playerId == null || questId == null) {
            return;
        }
        String key = normalize(questId);
        Map<String, String> player = cache.get(playerId);
        if (player != null) {
            player.remove(key);
        }
        if (!ready) {
            return;
        }
        asyncDbExecutor.method_244("questProgress.clear", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    DELETE FROM extraction_quest_progress
                    WHERE player_uuid = ? AND quest_id = ?
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, key);
                ps.executeUpdate();
            }
        }).exceptionally(error -> null);
    }

    private static String normalize(String questId) {
        return questId.trim().toLowerCase(Locale.ROOT);
    }
}
