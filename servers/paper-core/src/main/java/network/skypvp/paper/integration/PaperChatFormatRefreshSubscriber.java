package network.skypvp.paper.integration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.service.TabListService;
import network.skypvp.shared.ChatFormatRefreshEvent;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.RedisConnectionSettings;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public final class PaperChatFormatRefreshSubscriber implements AutoCloseable {
    private final RedisConnectionSettings settings;
    private final String ownServerId;
    private final PaperCorePlugin plugin;
    private final ChatFormatService formatService;
    private final Logger logger;
    private final AtomicBoolean reloadQueued = new AtomicBoolean(false);
    private volatile boolean running;
    private JedisPubSub pubSub;
    private Thread worker;

    public PaperChatFormatRefreshSubscriber(
            RedisConnectionSettings settings,
            String ownServerId,
            PaperCorePlugin plugin,
            ChatFormatService formatService,
            Logger logger
    ) {
        this.settings = settings;
        this.ownServerId = ownServerId;
        this.plugin = plugin;
        this.formatService = formatService;
        this.logger = logger;
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            this.worker = new Thread(this::runLoop, "SkyPvP-paper-chatformat-subscriber");
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
                        Objects.requireNonNull(PaperChatFormatRefreshSubscriber.this);
                    }

                    @Override
                    public void onMessage(String channel, String payload) {
                        PaperChatFormatRefreshSubscriber.this.handleMessage(payload);
                    }
                };
                this.logger.info("Subscribing to Redis chat-format channel '" + NetworkChannels.CHAT_FORMAT_REFRESH + "'");
                jedis.subscribe(this.pubSub, NetworkChannels.CHAT_FORMAT_REFRESH);
            } catch (Exception ex) {
                if (this.running) {
                    this.logger.warning("Chat-format Redis subscription failed (retrying in 5s): " + ex.getMessage());

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
            ChatFormatRefreshEvent event = JsonCodec.gson().fromJson(payload, ChatFormatRefreshEvent.class);
            if (event == null) {
                return;
            }

            if (this.ownServerId != null && this.ownServerId.equalsIgnoreCase(event.originServerId())) {
                return;
            }

            this.queueReload(event);
        } catch (Exception ex) {
            this.logger.warning("[ChatFormats] Failed to handle Redis message: " + ex.getMessage());
        }
    }

    private void queueReload(ChatFormatRefreshEvent event) {
        if (!this.reloadQueued.compareAndSet(false, true)) {
            return;
        }
        this.plugin.platformScheduler().runGlobal(() -> {
            this.reloadQueued.set(false);
            this.formatService.reload();
            String formatId = event.formatId() == null ? "all" : event.formatId();
            this.logger.info("[ChatFormats] Reloaded formats after " + event.action() + " on '" + formatId + "' from "
                    + event.originServerId());
            TabListService tabList = this.plugin.tabListService();
            if (tabList != null) {
                tabList.refresh();
            }
        });
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
