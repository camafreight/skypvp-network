package network.skypvp.paper.persistence;

public record PostgresConnectionSettings(String host, int port, String database, String username, String password) {
   public static PostgresConnectionSettings localDefaults() {
      return new PostgresConnectionSettings("127.0.0.1", 5432, "SkyPvP_network", "SkyPvP", "SkyPvP_local");
   }
}
