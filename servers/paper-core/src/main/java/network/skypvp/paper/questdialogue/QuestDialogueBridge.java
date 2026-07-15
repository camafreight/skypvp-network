package network.skypvp.paper.questdialogue;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Opens scripted quest dialogues from NPC/hologram actions. */
public interface QuestDialogueBridge {

    default void open(Player player, String dialogueId) {
        open(player, dialogueId, null, null);
    }

    default void open(Player player, String dialogueId, Location anchor) {
        open(player, dialogueId, anchor, null);
    }

    void open(Player player, String dialogueId, Location anchor, Entity speaker);
}
