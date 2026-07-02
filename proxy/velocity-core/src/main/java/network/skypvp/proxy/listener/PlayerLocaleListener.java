package network.skypvp.proxy.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import network.skypvp.proxy.service.PlayerLocaleService;

/**
 * Captures client locale from Velocity ({@link Player#getEffectiveLocale()}), updated on each backend connect.
 */
public final class PlayerLocaleListener {
    private final PlayerLocaleService localeService;

    public PlayerLocaleListener(PlayerLocaleService localeService) {
        this.localeService = localeService;
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        capture(event.getPlayer());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        capture(event.getPlayer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        this.localeService.evict(event.getPlayer().getUniqueId());
    }

    private void capture(Player player) {
        if (player != null) {
            this.localeService.capture(player.getUniqueId(), player.getEffectiveLocale());
        }
    }
}
