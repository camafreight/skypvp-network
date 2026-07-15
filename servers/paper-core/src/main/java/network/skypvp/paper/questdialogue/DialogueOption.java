package network.skypvp.paper.questdialogue;

import java.util.Objects;

/** Selectable dialogue choice. */
public record DialogueOption(String id, String label, String targetNodeId, String questActionId) {
    public DialogueOption {
        Objects.requireNonNull(id, "id");
        label = DialogueText.clamp(label);
    }
}
