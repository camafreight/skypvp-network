package network.skypvp.extraction.integration;

import java.util.Objects;
import java.util.logging.Logger;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.BreachPartyQueueDeployEvent;
import network.skypvp.shared.JsonCodec;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.RedisConnectionSettings;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/** Receives proxy party-queue deploy events and reserves a specific breach instance before members arrive. */
public final class BreachPartyQueueDeploySubscriber implements AutoCloseable {

    private final RedisConnectionSettings settings;
    private final String ownServerId;
    private final BreachEngine breachEngine;
    private final Logger logger;
    private volatile boolean running;
    private JedisPubSub pubSub;
    private Thread worker;

    public BreachPartyQueueDeploySubscriber(
            RedisConnectionSettings settings,
            String ownServerId,
            BreachEngine breachEngine,
            Logger logger
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ownServerId = ownServerId;
        this.breachEngine = Objects.requireNonNull(breachEngine, "breachEngine");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static BreachPartyQueueDeploySubscriber create(PaperCorePlugin core, BreachEngine breachEngine, Logger logger) {
        if (core == null || core.redisPublisher() == null) {
            return null;
        }
        network.skypvp.shared.RedisConnectionSettings settings = core.redisConnectionSettings();
        if (settings == null) {
            return null;
        }
        return new BreachPartyQueueDeploySubscriber(settings, core.serverId(), breachEngine, logger);
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            this.worker = new Thread(this::runLoop, "SkyPvP-breach-party-deploy-subscriber");
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
                    @Override
                    public void onMessage(String channel, String payload) {
                        if (NetworkChannels.BREACH_PARTY_DEPLOY_CANCEL.equals(channel)) {
                            BreachPartyQueueDeploySubscriber.this.handleCancelMessage(payload);
                        } else {
                            BreachPartyQueueDeploySubscriber.this.handleMessage(payload);
                        }
                    }
                };
                this.logger.info("Subscribing to Redis breach party deploy channels '"
                        + NetworkChannels.BREACH_PARTY_QUEUE_DEPLOY + "', '"
                        + NetworkChannels.BREACH_PARTY_DEPLOY_CANCEL + "'");
                jedis.subscribe(
                        this.pubSub,
                        NetworkChannels.BREACH_PARTY_QUEUE_DEPLOY,
                        NetworkChannels.BREACH_PARTY_DEPLOY_CANCEL
                );
            } catch (Exception exception) {
                if (this.running) {
                    this.logger.warning("Breach party deploy Redis subscription failed (retrying in 5s): " + exception.getMessage());
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void handleMessage(String payload) {
        try {
            BreachPartyQueueDeployEvent event = JsonCodec.gson().fromJson(payload, BreachPartyQueueDeployEvent.class);
            if (event == null || event.targetServerId() == null || event.instanceId() == null) {
                return;
            }
            if (this.ownServerId == null || !this.ownServerId.equalsIgnoreCase(event.targetServerId())) {
                return;
            }
            this.breachEngine.admitPartyFromQueueDeploy(event.partyId(), event.instanceId(), event.memberIds());
        } catch (RuntimeException exception) {
            this.logger.warning("Failed to handle breach party deploy event: " + exception.getMessage());
        }
    }

    private void handleCancelMessage(String payload) {
        try {
            network.skypvp.shared.BreachPartyDeployCancelEvent event =
                    JsonCodec.gson().fromJson(payload, network.skypvp.shared.BreachPartyDeployCancelEvent.class);
            if (event == null) {
                return;
            }
            if (event.targetServerId() != null
                    && this.ownServerId != null
                    && !this.ownServerId.equalsIgnoreCase(event.targetServerId())) {
                return;
            }
            this.breachEngine.cancelPartyQueueDeploy(event.partyId(), event.instanceId(), event.memberIds());
            this.logger.info("[Breach] apply deploy cancel reason=" + event.reason()
                    + " party=" + event.partyId() + " instance=" + event.instanceId());
        } catch (RuntimeException exception) {
            this.logger.warning("Failed to handle breach party deploy cancel: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        this.running = false;
        if (this.pubSub != null) {
            this.pubSub.unsubscribe();
        }
        if (this.worker != null) {
            this.worker.interrupt();
            try {
                this.worker.join(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
