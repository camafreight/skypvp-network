package network.skypvp.paper.integration;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.PlayerSocialSettingsService;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.RedisConnectionSettings;
import network.skypvp.shared.SocialSettingsRefreshEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class PaperSocialSettingsRefreshSubscriber implements AutoCloseable {
    private final RedisConnectionSettings settings;
    private final String ownServerId;
    private final PaperCorePlugin plugin;
    private final PlayerSocialSettingsService socialSettingsService;
    private final Logger logger;
    private volatile boolean running;
    private JedisPubSub pubSub;
    private Thread worker;

    public PaperSocialSettingsRefreshSubscriber(
            RedisConnectionSettings settings,
            String ownServerId,
            PaperCorePlugin plugin,
            PlayerSocialSettingsService socialSettingsService,
            Logger logger
    ) {
        this.settings = settings;
        this.ownServerId = ownServerId;
        this.plugin = plugin;
        this.socialSettingsService = socialSettingsService;
        this.logger = logger;
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            this.worker = new Thread(this::runLoop, "SkyPvP-paper-socialsettings-subscriber");
            this.worker.setDaemon(true);
            this.worker.start();
        }
    }

    private void runLoop() {
        while (this.running) {
            try (Jedis jedis = new Jedis(this.settings.host(), this.settings.port())) {
                if (!this.settings.sanitizedPassword().isBlank()) {
                    jedis.auth(this.settings.sanitizedPassword());
                }

                if (this.settings.database() != 0) {
                    jedis.select(this.settings.database());
                }

                this.pubSub = new JedisPubSub() {
                    {
                        Objects.requireNonNull(PaperSocialSettingsRefreshSubscriber.this);
                    }

                    @Override
                    public void onMessage(String channel, String payload) {
                        PaperSocialSettingsRefreshSubscriber.this.handleMessage(payload);
                    }
                };
                this.logger.info("Subscribing to Redis social-settings channel '" + NetworkChannels.SOCIAL_SETTINGS_REFRESH + "'");
                jedis.subscribe(this.pubSub, NetworkChannels.SOCIAL_SETTINGS_REFRESH);
            } catch (Exception ex) {
                if (this.running) {
                    this.logger.warning("Social-settings Redis subscription failed (retrying in 5s): " + ex.getMessage());

                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
    }

    private void handleMessage(String payload) {
        try {
            SocialSettingsRefreshEvent event = JsonCodec.gson().fromJson(payload, SocialSettingsRefreshEvent.class);
            if (event == null || event.playerUuid() == null || event.playerUuid().isBlank()) {
                return;
            }

            if (this.ownServerId != null && this.ownServerId.equalsIgnoreCase(event.originServerId())) {
                return;
            }

            UUID playerId = UUID.fromString(event.playerUuid());
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && online.isOnline()) {
                this.socialSettingsService.refresh(playerId);
            } else {
                this.socialSettingsService.evict(playerId);
            }
        } catch (IllegalArgumentException ex) {
            this.logger.warning("[SocialSettings] Invalid refresh payload: " + ex.getMessage());
        } catch (Exception ex) {
            this.logger.warning("[SocialSettings] Failed to handle Redis message: " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        this.running = false;
        if (this.pubSub != null) {
            try {
                this.pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }

        if (this.worker != null) {
            this.worker.interrupt();
        }
    }
}
