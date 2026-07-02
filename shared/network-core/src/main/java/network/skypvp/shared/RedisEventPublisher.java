package network.skypvp.shared;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

public final class RedisEventPublisher implements AutoCloseable {

    private final JedisPooled jedis;

    public RedisEventPublisher(RedisConnectionSettings settings) {
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .password(settings.sanitizedPassword().isBlank() ? null : settings.sanitizedPassword())
                .database(settings.database())
                .build();
        this.jedis = new JedisPooled(new HostAndPort(settings.host(), settings.port()), clientConfig);
    }

    public void publishJson(String channel, Object payload) {
        if (channel == null || channel.isBlank() || payload == null) {
            return;
        }
        jedis.publish(channel, JsonCodec.gson().toJson(payload));
    }

    /** Exposes the pooled client for direct key operations (e.g. short-lived web-auth OTP tokens). */
    public JedisPooled getJedis() {
        return jedis;
    }

    @Override
    public void close() {
        jedis.close();
    }
}
