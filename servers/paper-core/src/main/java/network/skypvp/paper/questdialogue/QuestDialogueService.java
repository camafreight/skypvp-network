package network.skypvp.paper.questdialogue;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.service.ActionBarService;
import network.skypvp.paper.service.ScoreboardService;
import network.skypvp.paper.waypoint.WaypointNavigatorService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Action-bar speech-bubble NPC dialogue core (independent from NpcLibrary; NPCs trigger via quest actions). */
public final class QuestDialogueService {

    /** Keep the override alive across heartbeats while the 2-tick ticker refreshes content. */
    private static final int OVERRIDE_DURATION_TICKS = 60;

    private final PaperCorePlugin core;
    private final QuestDialogueChoiceStore choiceStore;
    private final QuestDialoguePortrait portraits;
    private final Map<UUID, QuestDialogueSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Function<String, DialogueNode>> nodeLookups = new ConcurrentHashMap<>();
    private final Map<UUID, Runnable> finishCallbacks = new ConcurrentHashMap<>();
    private final Map<UUID, SavedLocomotion> savedLocomotion = new ConcurrentHashMap<>();
    private final Map<UUID, PlatformTask> animationTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> silhouetteOnly = new ConcurrentHashMap<>();
    private volatile QuestDialogueActionExecutor actionExecutor;
    private final QuestDialogueShoutService shoutService;

    public QuestDialogueService(PaperCorePlugin core, QuestDialogueChoiceStore choiceStore) {
        this.core = Objects.requireNonNull(core, "core");
        this.choiceStore = choiceStore == null ? new InMemoryQuestDialogueChoiceStore() : choiceStore;
        this.shoutService = new QuestDialogueShoutService(core);
        this.portraits = new QuestDialoguePortrait(core);
    }

    public QuestDialogueShoutService shouts() {
        return shoutService;
    }

    public QuestDialogueChoiceStore choiceStore() {
        return choiceStore;
    }

    /** Core waypoint navigator (preferred over {@link #shouts()} for guiding players to locations). */
    public WaypointNavigatorService navigator() {
        return core.waypointNavigator();
    }

    public boolean isInDialogue(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public void begin(
            Player player,
            String dialogueId,
            String npcDisplayName,
            DialogueNode startNode,
            Function<String, DialogueNode> nodeLookup,
            Runnable onFinish
    ) {
        begin(player, dialogueId, npcDisplayName, startNode, nodeLookup, onFinish, null, null);
    }

    public void begin(
            Player player,
            String dialogueId,
            String npcDisplayName,
            DialogueNode startNode,
            Function<String, DialogueNode> nodeLookup,
            Runnable onFinish,
            Entity speaker
    ) {
        begin(player, dialogueId, npcDisplayName, startNode, nodeLookup, onFinish, speaker, null);
    }

    public void begin(
            Player player,
            String dialogueId,
            String npcDisplayName,
            DialogueNode startNode,
            Function<String, DialogueNode> nodeLookup,
            Runnable onFinish,
            Entity speaker,
            PlayerProfile speakerProfile
    ) {
        if (player == null || startNode == null) {
            return;
        }
        if (isInDialogue(player.getUniqueId())) {
            cancel(player);
        }
        freeze(player);
        QuestDialogueSession session = new QuestDialogueSession(
                player.getUniqueId(),
                dialogueId,
                npcDisplayName,
                startNode
        );
        sessions.put(player.getUniqueId(), session);
        if (nodeLookup != null) {
            nodeLookups.put(player.getUniqueId(), nodeLookup);
        }
        if (onFinish != null) {
            finishCallbacks.put(player.getUniqueId(), onFinish);
        }

        PlayerProfile profile = speakerProfile != null ? speakerProfile : QuestDialoguePortrait.profileOf(speaker);
        boolean useSilhouette = profile == null;
        silhouetteOnly.put(player.getUniqueId(), useSilhouette);
        if (!useSilhouette) {
            portraits.spawn(player, profile);
        }

        ActionBarService actionBars = core.actionBarService();
        if (actionBars != null) {
            actionBars.suppress(player.getUniqueId());
        }
        // Ensure any leftover scoreboard dialogue overlay is gone.
        ScoreboardService scoreboards = core.scoreboardService();
        if (scoreboards != null) {
            scoreboards.clearDialogueFrame(player.getUniqueId());
            scoreboards.refreshPlayer(player);
        }
        pushFrame(player, session);
        startAnimationTicker(player);
    }

    /**
     * Successful close: runs finish callback (if any), restores locomotion / HUD, removes portrait.
     */
    public void end(Player player) {
        close(player, true);
    }

    /**
     * Abort dialogue (Q / quit) without running finish callbacks or choice side effects.
     */
    public void cancel(Player player) {
        close(player, false);
    }

    private void close(Player player, boolean runFinishCallback) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        stopAnimationTicker(id);
        sessions.remove(id);
        nodeLookups.remove(id);
        silhouetteOnly.remove(id);
        Runnable onFinish = finishCallbacks.remove(id);
        if (runFinishCallback && onFinish != null) {
            onFinish.run();
        }
        portraits.remove(id);
        unfreeze(player);

        ActionBarService actionBars = core.actionBarService();
        if (actionBars != null) {
            actionBars.clearOverride(id);
            actionBars.unsuppress(id);
            actionBars.refreshPlayer(player);
        }
        ScoreboardService scoreboards = core.scoreboardService();
        if (scoreboards != null) {
            scoreboards.clearDialogueFrame(id);
            scoreboards.refreshPlayer(player);
        }
    }

    public void tick(Player player) {
        QuestDialogueSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        while (session.tryAutoAdvanceLine(nowMillis)) {
            // chain lines that fit in the bubble without requiring Shift
        }
        portraits.tick(player);
        pushFrame(player, session);
    }

    public void handleShift(Player player) {
        QuestDialogueSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        QuestDialogueSession.AdvanceResult result = session.advance(System.currentTimeMillis());
        if (result.choiceMade() && result.chosenOption() != null) {
            DialogueOption chosen = result.chosenOption();
            choiceStore.writeChoice(player.getUniqueId(), session.dialogueId(), chosen.id(), "selected");
            boolean hasAction = chosen.questActionId() != null && !chosen.questActionId().isBlank();
            QuestDialogueActionExecutor executor = actionExecutor;
            if (executor != null && hasAction) {
                executor.execute(player, session.dialogueId(), chosen.questActionId());
            }
            String targetId = chosen.targetNodeId();
            if (targetId == null || targetId.isBlank()) {
                // Actions such as async upgrades may jump to a result node after execute() completes.
                if (!hasAction) {
                    end(player);
                }
                return;
            }
            jumpToNode(player, targetId);
            return;
        }
        if (result.ended()) {
            String nextId = result.nextNodeId();
            if (nextId != null && !nextId.isBlank()) {
                jumpToNode(player, nextId);
                return;
            }
            end(player);
            return;
        }
        pushFrame(player, session);
    }

    /** Jumps the active session to a node from the registered lookup, if still in dialogue. */
    public void jumpToNode(Player player, String nodeId) {
        if (player == null || nodeId == null || nodeId.isBlank()) {
            return;
        }
        QuestDialogueSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Function<String, DialogueNode> lookup = nodeLookups.get(player.getUniqueId());
        DialogueNode next = lookup == null ? null : lookup.apply(nodeId);
        if (next == null) {
            end(player);
            return;
        }
        session.jumpTo(next, System.currentTimeMillis());
        pushFrame(player, session);
    }

    public void handleMoveSelection(Player player, int delta) {
        QuestDialogueSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.awaitingChoice()) {
            if (session != null && delta > 0) {
                session.scrollDown();
                pushFrame(player, session);
            } else if (session != null && delta < 0) {
                session.scrollUp();
                pushFrame(player, session);
            }
            return;
        }
        session.moveSelection(delta, session.currentNode().options().size());
        pushFrame(player, session);
    }

    public Optional<QuestDialogueSession> session(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public void bindActionExecutor(QuestDialogueActionExecutor executor) {
        this.actionExecutor = executor;
    }

    private void pushFrame(Player player, QuestDialogueSession session) {
        boolean silhouette = silhouetteOnly.getOrDefault(player.getUniqueId(), true)
                || !portraits.hasPortrait(player.getUniqueId());
        Component bubble = QuestDialogueBubbleRenderer.compose(session, System.currentTimeMillis(), silhouette);
        ActionBarService actionBars = core.actionBarService();
        if (actionBars != null) {
            actionBars.pushOverride(player, bubble, OVERRIDE_DURATION_TICKS);
        } else {
            player.sendActionBar(bubble);
        }
    }

    private void startAnimationTicker(Player player) {
        UUID playerId = player.getUniqueId();
        stopAnimationTicker(playerId);
        PlatformTask task = core.platformScheduler().runOnPlayerTimer(player, () -> {
            if (!isInDialogue(playerId)) {
                stopAnimationTicker(playerId);
                return;
            }
            tick(player);
        }, 2L, 2L);
        animationTasks.put(playerId, task);
    }

    private void stopAnimationTicker(UUID playerId) {
        PlatformTask task = animationTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void freeze(Player player) {
        savedLocomotion.put(player.getUniqueId(), new SavedLocomotion(player.getWalkSpeed(), player.getFlySpeed()));
        player.setWalkSpeed(0.0F);
        player.setFlySpeed(0.0F);
        player.setSprinting(false);
    }

    private void unfreeze(Player player) {
        SavedLocomotion saved = savedLocomotion.remove(player.getUniqueId());
        if (saved != null) {
            player.setWalkSpeed(saved.walkSpeed());
            player.setFlySpeed(saved.flySpeed());
        }
    }

    private record SavedLocomotion(float walkSpeed, float flySpeed) {
    }
}
