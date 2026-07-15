package network.skypvp.paper.questdialogue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory choice store until Postgres wiring lands. */
public final class InMemoryQuestDialogueChoiceStore implements QuestDialogueChoiceStore {

    private final Map<UUID, Map<String, Map<String, String>>> data = new ConcurrentHashMap<>();

    @Override
    public Optional<String> readChoice(UUID playerId, String dialogueId, String optionId) {
        return Optional.ofNullable(data
                .getOrDefault(playerId, Map.of())
                .getOrDefault(dialogueId, Map.of())
                .get(optionId));
    }

    @Override
    public void writeChoice(UUID playerId, String dialogueId, String optionId, String value) {
        data.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(dialogueId, ignored -> new ConcurrentHashMap<>())
                .put(optionId, value);
    }

    @Override
    public Map<String, String> readDialogueState(UUID playerId, String dialogueId) {
        return Map.copyOf(data.getOrDefault(playerId, Map.of()).getOrDefault(dialogueId, Map.of()));
    }
}
