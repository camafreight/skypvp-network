package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.database.AsyncDbExecutor;

/** Persists quest dialogue choices per player. */
public final class QuestDialogueRepository {

    private final AsyncDbExecutor asyncDbExecutor;
    private volatile boolean ready;

    public QuestDialogueRepository(AsyncDbExecutor asyncDbExecutor) {
        this.asyncDbExecutor = Objects.requireNonNull(asyncDbExecutor, "asyncDbExecutor");
    }

    public CompletableFuture<Void> ensureSchema() {
        return asyncDbExecutor.method_244("questDialogue.ensureSchema", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS extraction_quest_dialogue_choices (
                        player_uuid UUID NOT NULL REFERENCES extraction_player_profiles(player_uuid) ON DELETE CASCADE,
                        dialogue_id VARCHAR(64) NOT NULL,
                        option_id VARCHAR(64) NOT NULL,
                        value VARCHAR(128) NOT NULL DEFAULT 'selected',
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (player_uuid, dialogue_id, option_id)
                    )
                    """
            )) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    CREATE INDEX IF NOT EXISTS idx_extraction_quest_dialogue_choices_player
                        ON extraction_quest_dialogue_choices (player_uuid)
                    """
            )) {
                ps.executeUpdate();
            }
            ready = true;
        });
    }

    public boolean isReady() {
        return ready;
    }

    public CompletableFuture<Optional<String>> loadChoice(UUID playerId, String dialogueId, String optionId) {
        if (!ready || playerId == null || dialogueId == null || optionId == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return asyncDbExecutor.supply("questDialogue.loadChoice", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    SELECT value FROM extraction_quest_dialogue_choices
                    WHERE player_uuid = ? AND dialogue_id = ? AND option_id = ?
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, dialogueId);
                ps.setString(3, optionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(rs.getString("value"));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Map<String, String>> loadDialogueState(UUID playerId, String dialogueId) {
        if (!ready || playerId == null || dialogueId == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return asyncDbExecutor.supply("questDialogue.loadDialogueState", connection -> {
            Map<String, String> state = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    SELECT option_id, value FROM extraction_quest_dialogue_choices
                    WHERE player_uuid = ? AND dialogue_id = ?
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, dialogueId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        state.put(rs.getString("option_id"), rs.getString("value"));
                    }
                }
            }
            return Map.copyOf(state);
        });
    }

    public CompletableFuture<Void> writeChoice(UUID playerId, String dialogueId, String optionId, String value) {
        if (!ready || playerId == null || dialogueId == null || optionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        String stored = value == null ? "selected" : value;
        return asyncDbExecutor.method_244("questDialogue.writeChoice", connection -> {
            try (PreparedStatement ensure = connection.prepareStatement(
                    "INSERT INTO extraction_player_profiles (player_uuid, revision, updated_at) "
                            + "VALUES (?, 0, NOW()) ON CONFLICT (player_uuid) DO NOTHING"
            )) {
                ensure.setObject(1, playerId);
                ensure.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO extraction_quest_dialogue_choices (player_uuid, dialogue_id, option_id, value, updated_at)
                    VALUES (?, ?, ?, ?, NOW())
                    ON CONFLICT (player_uuid, dialogue_id, option_id)
                    DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
                    """
            )) {
                ps.setObject(1, playerId);
                ps.setString(2, dialogueId);
                ps.setString(3, optionId);
                ps.setString(4, stored);
                ps.executeUpdate();
            }
        });
    }
}
