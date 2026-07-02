package network.skypvp.paper.integration;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings;
import java.util.UUID;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import network.skypvp.paper.event.PlayerClientLocaleChangeEvent;
import network.skypvp.paper.service.PlayerLocaleService;
import network.skypvp.shared.chat.ClientLocaleUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Updates {@link PlayerLocaleService} when the client sends a Client Settings packet (language change).
 */
public final class PlayerLocaleListener {
    private final Plugin plugin;
    private final PlayerLocaleService localeService;
    private PacketListenerAbstract listener;

    public PlayerLocaleListener(Plugin plugin, PlayerLocaleService localeService) {
        this.plugin = plugin;
        this.localeService = localeService;
    }

    public void start() {
        if (this.listener != null || !PacketEventsBridge.isAvailable()) {
            return;
        }
        this.listener = new LocalePacketListener();
        PacketEvents.getAPI().getEventManager().registerListener(this.listener);
    }

    public void shutdown() {
        if (this.listener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this.listener);
            } catch (RuntimeException ignored) {
            }
            this.listener = null;
        }
    }

    private final class LocalePacketListener extends PacketListenerAbstract {
        LocalePacketListener() {
            super(PacketListenerPriority.MONITOR);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Play.Client.CLIENT_SETTINGS) {
                return;
            }
            WrapperPlayClientSettings settings = new WrapperPlayClientSettings(event);
            String locale = settings.getLocale();
            UUID uuid = event.getUser() == null ? null : event.getUser().getUUID();
            if (uuid == null || locale == null || locale.isBlank()) {
                return;
            }
            String previous = PlayerLocaleListener.this.localeService.locale(uuid);
            PlayerLocaleListener.this.localeService.update(uuid, locale);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                PlayerLocaleListener.this.plugin.getLogger().fine(
                        "[chat-translation] Locale for " + player.getName() + ": " + locale
                );
                if (!ClientLocaleUtil.sameLanguage(previous, locale)) {
                    Bukkit.getScheduler().runTask(
                            PlayerLocaleListener.this.plugin,
                            () -> Bukkit.getPluginManager().callEvent(
                                    new PlayerClientLocaleChangeEvent(player, previous, locale)
                            )
                    );
                }
            }
        }
    }
}
