package network.skypvp.paper.questdialogue;

import java.util.List;
import java.util.Objects;

/** One node in a branching dialogue script. */
public record DialogueNode(
        String id,
        String speakerDisplayName,
        List<String> lines,
        List<DialogueOption> options,
        String nextNodeId
) {
    public DialogueNode {
        Objects.requireNonNull(id, "id");
        speakerDisplayName = speakerDisplayName == null ? "NPC" : speakerDisplayName;
        lines = DialogueText.wrapLines(lines);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public boolean hasOptions() {
        return !options.isEmpty();
    }
}
