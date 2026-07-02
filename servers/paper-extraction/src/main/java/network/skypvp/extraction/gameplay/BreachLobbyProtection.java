package network.skypvp.extraction.gameplay;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachState;
import org.bukkit.entity.Player;

public final class BreachLobbyProtection {

    private BreachLobbyProtection() {
    }

    public static boolean isLobbySafe(BreachEngine engine, Player player) {
        Objects.requireNonNull(engine, "engine");
        if (player == null) {
            return false;
        }
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty()) {
            return true;
        }
        return isLobbySafe(instance.get(), player);
    }

    public static boolean isLobbySafe(BreachInstance instance, Player player) {
        Objects.requireNonNull(instance, "instance");
        if (player == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (instance.hasExtracted(playerId)) {
            return true;
        }
        return switch (instance.state()) {
            case ENDING, RESETTING -> true;
            case ACTIVE -> instance.isSpectating(playerId) || instance.isPendingJoin(playerId);
            case WAITING, STARTING -> true;
        };
    }
}
