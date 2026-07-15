package network.skypvp.paper.questdialogue;

import org.bukkit.entity.Player;

/** Executes side effects when a dialogue choice with a quest action id is confirmed. */
public interface QuestDialogueActionExecutor {

    void execute(Player player, String dialogueId, String questActionId);
}
