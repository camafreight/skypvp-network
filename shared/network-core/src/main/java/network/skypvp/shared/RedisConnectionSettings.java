package network.skypvp.shared;

public record RedisConnectionSettings(
        String host,
        int port,
        String password,
        int database
) {

    public static RedisConnectionSettings localDefaults() {
        return new RedisConnectionSettings("127.0.0.1", 6379, "", 0);
    }

    public String sanitizedPassword() {
        return password == null ? "" : password;
    }
}
