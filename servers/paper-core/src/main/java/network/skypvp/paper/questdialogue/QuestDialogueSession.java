package network.skypvp.paper.questdialogue;

import java.util.Objects;
import java.util.UUID;
/** Active dialogue state for one player. */
public final class QuestDialogueSession {

    /** Milliseconds per revealed character (~40 chars/sec). */
    public static final long MS_PER_CHAR = 50L;

    private final UUID playerId;
    private final String dialogueId;
    private final String npcDisplayName;
    private DialogueNode currentNode;
    private int lineIndex;
    private int selectedOptionIndex;
    /** Speech-line window offset while reading (non-choice). */
    private int scrollOffset;
    /** First visible choice index when options exceed {@link QuestDialogueBubbleRenderer#CHOICE_LINE_COUNT}. */
    private int choiceScrollOffset;
    private long lineRevealStartedAt;
    private long[] optionRevealStartedAt;
    private boolean awaitingAdvance;
    private boolean awaitingChoice;
    private boolean finished;

    public QuestDialogueSession(UUID playerId, String dialogueId, String npcDisplayName, DialogueNode startNode) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.dialogueId = Objects.requireNonNull(dialogueId, "dialogueId");
        this.npcDisplayName = npcDisplayName == null ? "NPC" : npcDisplayName;
        this.currentNode = Objects.requireNonNull(startNode, "startNode");
        this.lineRevealStartedAt = System.currentTimeMillis();
        resetOptionReveal();
        enterNode(startNode, lineRevealStartedAt);
    }

    public UUID playerId() {
        return playerId;
    }

    public String dialogueId() {
        return dialogueId;
    }

    public String npcDisplayName() {
        return npcDisplayName;
    }

    public DialogueNode currentNode() {
        return currentNode;
    }

    public int scrollOffset() {
        return scrollOffset;
    }

    public int choiceScrollOffset() {
        return choiceScrollOffset;
    }

    public void scrollDown() {
        scrollOffset++;
    }

    public void scrollUp() {
        scrollOffset = Math.max(0, scrollOffset - 1);
    }

    public int selectedOptionIndex() {
        return selectedOptionIndex;
    }

    /**
     * Moves the highlighted choice. Wraps at ends (same as W/S). Keeps the selection inside
     * the visible choice window when there are more options than fit on screen.
     */
    public void moveSelection(int delta, int optionCount) {
        if (optionCount <= 0) {
            return;
        }
        selectedOptionIndex = Math.floorMod(selectedOptionIndex + delta, optionCount);
        ensureChoiceVisible(optionCount);
    }

    private void ensureChoiceVisible(int optionCount) {
        int window = QuestDialogueBubbleRenderer.CHOICE_LINE_COUNT;
        if (optionCount <= window) {
            choiceScrollOffset = 0;
            return;
        }
        int maxOffset = optionCount - window;
        if (selectedOptionIndex < choiceScrollOffset) {
            choiceScrollOffset = selectedOptionIndex;
        } else if (selectedOptionIndex >= choiceScrollOffset + window) {
            choiceScrollOffset = selectedOptionIndex - window + 1;
        }
        choiceScrollOffset = Math.max(0, Math.min(choiceScrollOffset, maxOffset));
    }

    public boolean awaitingAdvance() {
        return awaitingAdvance;
    }

    public boolean awaitingChoice() {
        return awaitingChoice;
    }

    public boolean finished() {
        return finished;
    }

    public long lineRevealMillis() {
        return lineRevealStartedAt;
    }

    public long optionRevealMillis(int index) {
        if (optionRevealStartedAt == null || index < 0 || index >= optionRevealStartedAt.length) {
            return lineRevealStartedAt;
        }
        return optionRevealStartedAt[index];
    }

    public String currentLineText() {
        if (currentNode.lines().isEmpty()) {
            return "";
        }
        int safeIndex = Math.min(lineIndex, currentNode.lines().size() - 1);
        return currentNode.lines().get(safeIndex);
    }

    public int lineIndex() {
        return lineIndex;
    }

    public boolean isCurrentLineFullyRevealed(long nowMillis) {
        return isFullyRevealed(currentLineText(), lineRevealStartedAt, nowMillis);
    }

    /**
     * Chains to the next dialogue line when the current line is done and the next line still fits on the board.
     *
     * @return {@code true} when a line was advanced automatically
     */
    public boolean tryAutoAdvanceLine(long nowMillis) {
        if (finished || awaitingChoice) {
            return false;
        }
        if (!isCurrentLineFullyRevealed(nowMillis)) {
            return false;
        }
        if (lineIndex + 1 >= currentNode.lines().size()) {
            awaitingAdvance = true;
            return false;
        }
        int visibleAfterAdvance = lineIndex + 2;
        if (visibleAfterAdvance > QuestDialogueBubbleRenderer.SPEECH_LINE_COUNT) {
            awaitingAdvance = true;
            return false;
        }
        lineIndex++;
        lineRevealStartedAt = nowMillis;
        scrollOffset = 0;
        awaitingAdvance = false;
        return true;
    }


    /** Advance typewriter line or finish node when fully revealed. Returns chosen option id when a choice is confirmed. */
    public AdvanceResult advance(long nowMillis) {
        if (finished) {
            return AdvanceResult.none();
        }
        if (awaitingChoice) {
            DialogueOption option = currentNode.options().get(selectedOptionIndex);
            if (!isFullyRevealed(option.label(), optionRevealMillis(selectedOptionIndex), nowMillis)) {
                skipReveal(option.label(), selectedOptionIndex, nowMillis);
                return AdvanceResult.none();
            }
            return AdvanceResult.choice(option);
        }
        if (!isFullyRevealed(currentLineText(), lineRevealStartedAt, nowMillis)) {
            skipReveal(currentLineText(), -1, nowMillis);
            return AdvanceResult.none();
        }
        if (lineIndex + 1 < currentNode.lines().size()) {
            lineIndex++;
            lineRevealStartedAt = nowMillis;
            scrollOffset = 0;
            awaitingAdvance = false;
            return AdvanceResult.none();
        }
        if (currentNode.hasOptions()) {
            awaitingChoice = true;
            awaitingAdvance = false;
            selectedOptionIndex = 0;
            choiceScrollOffset = 0;
            resetOptionReveal(nowMillis);
            return AdvanceResult.none();
        }
        finished = true;
        return AdvanceResult.end(currentNode.nextNodeId());
    }

    public void jumpTo(DialogueNode node, long nowMillis) {
        this.currentNode = Objects.requireNonNull(node, "node");
        this.lineIndex = 0;
        this.selectedOptionIndex = 0;
        this.scrollOffset = 0;
        this.choiceScrollOffset = 0;
        this.lineRevealStartedAt = nowMillis;
        this.finished = false;
        resetOptionReveal(nowMillis);
        enterNode(node, nowMillis);
    }

    private void enterNode(DialogueNode node, long nowMillis) {
        if (!node.lines().isEmpty()) {
            awaitingAdvance = false;
            awaitingChoice = false;
            lineRevealStartedAt = nowMillis;
        } else if (node.hasOptions()) {
            awaitingAdvance = false;
            awaitingChoice = true;
            selectedOptionIndex = 0;
            choiceScrollOffset = 0;
        } else {
            awaitingAdvance = false;
            awaitingChoice = false;
            finished = true;
        }
    }

    private void skipReveal(String text, int optionIndex, long nowMillis) {
        long duration = Math.max(MS_PER_CHAR, text.length() * MS_PER_CHAR);
        long startedAt = nowMillis - duration;
        if (optionIndex >= 0 && optionRevealStartedAt != null && optionIndex < optionRevealStartedAt.length) {
            optionRevealStartedAt[optionIndex] = startedAt;
        } else {
            lineRevealStartedAt = startedAt;
        }
    }

    private void resetOptionReveal() {
        resetOptionReveal(System.currentTimeMillis());
    }

    private void resetOptionReveal(long nowMillis) {
        optionRevealStartedAt = new long[currentNode.options().size()];
        for (int i = 0; i < optionRevealStartedAt.length; i++) {
            optionRevealStartedAt[i] = nowMillis + (i * 180L);
        }
    }

    private static boolean isFullyRevealed(String text, long startedAt, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        long elapsed = Math.max(0L, nowMillis - startedAt);
        return elapsed >= text.length() * MS_PER_CHAR;
    }

    public record AdvanceResult(boolean ended, boolean choiceMade, String nextNodeId, DialogueOption chosenOption) {
        public static AdvanceResult none() {
            return new AdvanceResult(false, false, null, null);
        }

        public static AdvanceResult end(String nextNodeId) {
            return new AdvanceResult(true, false, nextNodeId, null);
        }

        public static AdvanceResult choice(DialogueOption option) {
            return new AdvanceResult(false, true, option.targetNodeId(), option);
        }
    }
}
