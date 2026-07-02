package network.skypvp.paper.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import network.skypvp.paper.gamemode.api.LocalChatScope;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class LocalChatScopeSupport {
    private LocalChatScopeSupport() {
    }

    public static void restrictGlobalAudience(AsyncChatEvent event) {
        for (RegisteredServiceProvider<LocalChatScope> provider
                : Bukkit.getServicesManager().getRegistrations(LocalChatScope.class)) {
            provider.getProvider().restrictGlobalAudience(event);
        }
    }

    public static boolean skipGlobalRedisBroadcast() {
        for (RegisteredServiceProvider<LocalChatScope> provider
                : Bukkit.getServicesManager().getRegistrations(LocalChatScope.class)) {
            if (provider.getProvider().skipGlobalRedisBroadcast()) {
                return true;
            }
        }
        return false;
    }
}
