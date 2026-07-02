package network.skypvp.core.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class DatabaseManager implements AutoCloseable {
   private final HikariDataSource dataSource;
   private final Logger logger;

   public DatabaseManager(String host, int port, String database, String username, String password, String applicationName, int maxPoolSize) {
      this.logger = Logger.getLogger("DatabaseManager-" + applicationName);
      HikariConfig config = new HikariConfig();
      config.setDriverClassName("org.postgresql.Driver");
      config.setJdbcUrl(
            "jdbc:postgresql://" + host + ":" + port + "/" + database
                  + "?sslmode=disable&tcpKeepAlive=true&connectTimeout=10&socketTimeout=30"
      );
      config.setUsername(username);
      config.setPassword(password);
      config.setPoolName("SkyPvP-pool-" + applicationName);
      config.setMaximumPoolSize(maxPoolSize);
      config.setMinimumIdle(Math.max(1, Math.min(2, maxPoolSize)));
      config.setConnectionTimeout(10000L);
      config.setIdleTimeout(300000L);
      config.setMaxLifetime(600000L);
      config.setValidationTimeout(5000L);
      config.setConnectionTestQuery("SELECT 1");
      config.addDataSourceProperty("ApplicationName", applicationName);
      config.addDataSourceProperty("reWriteBatchedInserts", "true");
      this.dataSource = new HikariDataSource(config);
   }

   public void validateConnection() {
      try (
         Connection connection = this.dataSource.getConnection();
         Statement statement = connection.createStatement()
      ) {
         statement.execute("SELECT 1");
      } catch (SQLException exception) {
         throw new RuntimeException("PostgreSQL validation query failed", exception);
      }
   }

   public void runMigrations() {
      this.ensureMigrationTable();

      try {
         try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
               this.applyMigration(connection, 1, "migrations/V1__canonical_schema.sql");
               this.applyMigration(connection, 2, "migrations/V2__extraction_pim.sql");
               this.applyMigration(connection, 3, "migrations/V3__player_social_settings.sql");
               this.applyMigration(connection, 4, "migrations/V4__vault_unlocked_rows.sql");
               this.applyMigration(connection, 5, "migrations/V5__chat_core.sql");
               this.         applyMigration(connection, 6, "migrations/V6__chat_translation.sql");
         applyMigration(connection, 7, "migrations/V7__network_nametags.sql");
         connection.commit();
            } catch (SQLException e) {
               connection.rollback();
               throw new RuntimeException("Failed to apply database migrations", e);
            } finally {
               connection.setAutoCommit(true);
            }
         }
      } catch (SQLException e) {
         throw new RuntimeException("Failed to apply database migrations", e);
      }
   }

   private void ensureMigrationTable() {
      String createSql = "CREATE TABLE IF NOT EXISTS paper_schema_migrations (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())";

      try {
         try (
            Connection connection = this.dataSource.getConnection();
            Statement statement = connection.createStatement()
         ) {
            statement.execute(createSql);
         }
      } catch (SQLException e) {
         throw new RuntimeException("Failed to create migrations table", e);
      }
   }

   private void applyMigration(Connection connection, int version, String resourcePath) throws SQLException {
      try (Statement checkStatement = connection.createStatement()) {
         ResultSet rs = checkStatement.executeQuery("SELECT 1 FROM paper_schema_migrations WHERE version = " + version);
         if (rs.next()) {
            this.logger.info("Migration V" + version + " already applied, skipping.");
            return;
         }
      }

      String sql = this.loadMigrationScript(resourcePath);

      try (Statement statement = connection.createStatement()) {
         statement.execute(sql);
         statement.execute("INSERT INTO paper_schema_migrations (version) VALUES (" + version + ")");
      }

      this.logger.info("Applied migration V" + version + " from " + resourcePath);
   }

   public Connection getConnection() throws SQLException {
      return this.dataSource.getConnection();
   }

   @Override
   public void close() {
      if (this.dataSource != null && !this.dataSource.isClosed()) {
         this.dataSource.close();
      }
   }

   private String loadMigrationScript(String resourcePath) {
      try {
         String result;
         try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
               throw new RuntimeException("Migration script not found on classpath: " + resourcePath);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
               result = reader.lines().collect(Collectors.joining("\n"));
            }
         }
         return result;
      } catch (IOException e) {
         throw new RuntimeException("Failed to read migration script: " + resourcePath, e);
      }
   }
}
