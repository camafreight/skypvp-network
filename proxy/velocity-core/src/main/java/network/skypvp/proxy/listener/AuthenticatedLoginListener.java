package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.shared.ServerTextUtil;

/**
 * Blocks offline-mode (cracked) Java UUIDs at the proxy gate. Mojang-authenticated players use UUID v4;
 * Floodgate Bedrock players use UUID v0 — both are allowed.
 */
public final class AuthenticatedLoginListener {

    private static final Component DENY_MESSAGE = ServerTextUtil.miniMessageComponent(
        "\n<#CC0000><bold>Java account required</bold><reset>\n\n"
            + "<#888888>Only authenticated Minecraft Java accounts can join this network.<reset>\n"
            + "<#888888>Please sign in with a valid Microsoft/Mojang account.<reset>\n"
    );

    private final ProxyBootstrapConfig config;

    public AuthenticatedLoginListener(ProxyBootstrapConfig config) {
        this.config = config;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (!this.config.requireAuthenticatedAccounts) {
            return;
        }
        UUID uuid = event.getUniqueId();
        if (uuid == null) {
            return;
        }
        if (uuid.version() == 3) {
            event.setResult(PreLoginComponentResult.denied(DENY_MESSAGE));
        }
    }
}
