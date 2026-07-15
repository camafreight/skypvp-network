package network.skypvp.extraction.integration;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.logging.Logger;
import network.skypvp.extraction.engine.BreachEngine;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Handles proxy → backend control messages for {@code /breach play} matchmaking fallbacks. */
public final class BreachProxyRouteListener implements PluginMessageListener {

    private final BreachEngine breachEngine;
    private final Logger logger;

    public BreachProxyRouteListener(BreachEngine breachEngine, Logger logger) {
        this.breachEngine = breachEngine;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null || message == null || message.length == 0) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = input.readUTF();
            if ("BREACH_PLAY_LOCAL".equalsIgnoreCase(action)) {
                String mapId = input.readUTF();
                breachEngine.continueLocalPlay(player, mapId);
            }
        } catch (IOException exception) {
            logger.warning("Failed to decode breach proxy route message: " + exception.getMessage());
        }
    }
}
