package network.skypvp.paper.questdialogue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persists per-player dialogue choices and quest flags. */
public interface QuestDialogueChoiceStore {

    Optional<String> readChoice(UUID playerId, String dialogueId, String optionId);

    void writeChoice(UUID playerId, String dialogueId, String optionId, String value);

    Map<String, String> readDialogueState(UUID playerId, String dialogueId);
}
