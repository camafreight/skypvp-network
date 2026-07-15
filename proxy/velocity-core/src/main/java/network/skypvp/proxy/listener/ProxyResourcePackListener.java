package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.configuration.PlayerConfigurationEvent;
import java.util.Objects;
import network.skypvp.proxy.resourcepack.ProxyResourcePackService;

/**
 * Holds the configuration/login phase until the network resource pack applies (or kicks on failure).
 */
public final class ProxyResourcePackListener {

    private final ProxyResourcePackService service;

    public ProxyResourcePackListener(ProxyResourcePackService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Subscribe
    public void onConfiguration(PlayerConfigurationEvent event, Continuation continuation) {
        service.holdConfiguration(event.player(), continuation);
    }

    @Subscribe
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        service.handleStatus(event.getPlayer(), event.getPackId(), event.getStatus());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        service.handleDisconnect(event.getPlayer().getUniqueId());
    }
}
