package network.skypvp.extraction.gameplay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Keeps sprint intent alive until custom stamina reaches zero. Vanilla food thresholds (~6 drumsticks) no longer
 * gate sprint — the server reasserts sprint each tick while {@link BreachStaminaService} maps stamina onto the food bar.
 */
public final class BreachStaminaSprintBridge {

    private final Map<UUID, Boolean> sprintIntent = new ConcurrentHashMap<>();

    public BreachStaminaSprintBridge() {
    }

    public void recordSprintIntent(Player player, boolean sprinting) {
        if (player == null) {
            return;
        }
        sprintIntent.put(player.getUniqueId(), sprinting);
    }

    public void clear(Player player) {
        if (player != null) {
            sprintIntent.remove(player.getUniqueId());
        }
    }

    public void applySprintGate(Player player, BreachStaminaService staminaService) {
        if (player == null || staminaService == null || !staminaService.isEnrolled(player.getUniqueId())) {
            return;
        }
        boolean wantsSprint = sprintIntent.getOrDefault(player.getUniqueId(), player.isSprinting());
        boolean allowed = staminaService.isSprintAllowed(player);
        if (allowed && wantsSprint) {
            player.setSprinting(true);
        } else if (!allowed && player.isSprinting()) {
            player.setSprinting(false);
            sprintIntent.put(player.getUniqueId(), false);
        }
    }

}
