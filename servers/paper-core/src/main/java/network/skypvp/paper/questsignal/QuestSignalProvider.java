package network.skypvp.paper.questsignal;

import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * A quest-side signal source for {@link QuestSignalService}. Register one per static quest
 * (e.g. {@code scrapper_main_dialogue_quest}); the service calls {@link #evaluate(Player)} on the
 * player's region thread whenever the player enters {@link #worldName()} (join or world change),
 * and on manual {@link QuestSignalService#refresh(Player)}.
 *
 * <p>Return {@link Optional#empty()} when the quest currently has nothing to say — the provider
 * decides the condition (items buffered, task ready, …), the service handles delivery, so the
 * whole pipeline stays modular: the quest never touches displays or chat directly.
 */
public interface QuestSignalProvider {

    /** Stable quest id, e.g. {@code scrapper_main_dialogue_quest}. */
    String questId();

    /** Bukkit world the signal is delivered in (e.g. the hub world {@code world}). */
    String worldName();

    /** Called on the player's region thread. Empty = no signal right now. */
    Optional<QuestSignalDelivery> evaluate(Player player);
}
