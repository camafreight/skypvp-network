package network.skypvp.paper.questdialogue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.repository.QuestDialogueRepository;

/** Postgres-backed dialogue choice store with a small read-through cache. */
public final class PostgresQuestDialogueChoiceStore implements QuestDialogueChoiceStore {

    private final QuestDialogueRepository repository;
    private final Map<UUID, Map<String, Map<String, String>>> cache = new ConcurrentHashMap<>();

    public PostgresQuestDialogueChoiceStore(QuestDialogueRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<String> readChoice(UUID playerId, String dialogueId, String optionId) {
        if (playerId == null || dialogueId == null || optionId == null) {
            return Optional.empty();
        }
        Map<String, Map<String, String>> player = cache.get(playerId);
        if (player != null) {
            Map<String, String> dialogue = player.get(dialogueId);
            if (dialogue != null && dialogue.containsKey(optionId)) {
                return Optional.ofNullable(dialogue.get(optionId));
            }
        }
        try {
            return repository.loadChoice(playerId, dialogueId, optionId).join();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void writeChoice(UUID playerId, String dialogueId, String optionId, String value) {
        if (playerId == null || dialogueId == null || optionId == null) {
            return;
        }
        String stored = value == null ? "selected" : value;
        cache.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(dialogueId, ignored -> new ConcurrentHashMap<>())
                .put(optionId, stored);
        CompletableFuture<Void> write = repository.writeChoice(playerId, dialogueId, optionId, stored);
        write.exceptionally(error -> null);
    }

    @Override
    public Map<String, String> readDialogueState(UUID playerId, String dialogueId) {
        if (playerId == null || dialogueId == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> player = cache.get(playerId);
        if (player != null && player.containsKey(dialogueId)) {
            return Map.copyOf(player.get(dialogueId));
        }
        try {
            Map<String, String> loaded = repository.loadDialogueState(playerId, dialogueId).join();
            if (!loaded.isEmpty()) {
                cache.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                        .put(dialogueId, new ConcurrentHashMap<>(loaded));
            }
            return loaded;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    public void warmCache(UUID playerId, String dialogueId) {
        if (playerId == null || dialogueId == null) {
            return;
        }
        repository.loadDialogueState(playerId, dialogueId).thenAccept(state -> {
            if (!state.isEmpty()) {
                cache.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                        .put(dialogueId, new ConcurrentHashMap<>(state));
            }
        });
    }
}
