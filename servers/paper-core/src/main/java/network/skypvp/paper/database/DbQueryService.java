package network.skypvp.paper.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.plugin.Plugin;

public class DbQueryService {
   private final DatabaseManager databaseManager;
   private final AsyncDbExecutor asyncDbExecutor;
   private final Logger logger;

   public DbQueryService(DatabaseManager databaseManager, Plugin plugin, ServerPlatform scheduler, Logger logger) {
      this.databaseManager = databaseManager;
      this.asyncDbExecutor = new AsyncDbExecutor(databaseManager, plugin, scheduler, logger);
      this.logger = logger;
   }

   public void querySync(String sql, DbQueryService.QueryCallback callback) {
      try {
         this.asyncDbExecutor.method_244("db.querySync", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               callback.execute(ps);
            }
         }).get();
      } catch (Exception var4) {
         this.logger.warning("Failed to execute query: " + var4.getMessage());
      }
   }

   public void querySyncWithParams(String sql, DbQueryService.ParamQueryCallback callback) {
      try {
         this.asyncDbExecutor.method_244("db.querySyncWithParams", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               callback.execute(ps);
            }
         }).get();
      } catch (Exception var4) {
         this.logger.warning("Failed to execute query: " + var4.getMessage());
      }
   }

   public int updateSync(String sql, DbQueryService.ParamQueryCallback callback) {
      try {
         return this.asyncDbExecutor.<Integer>supply("db.updateSync", connection -> {
            Integer t$;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               callback.execute(ps);
               t$ = ps.executeUpdate();
            }

            return t$;
         }).get();
      } catch (Exception var4) {
         this.logger.warning("Failed to execute update: " + var4.getMessage());
         return 0;
      }
   }

   public CompletableFuture<Void> queryAsync(String sql, DbQueryService.ParamQueryCallback callback) {
      return this.asyncDbExecutor.method_244("db.queryAsync", connection -> {
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            callback.execute(ps);
         }
      });
   }

   public CompletableFuture<Integer> updateAsync(String sql, DbQueryService.ParamQueryCallback callback) {
      return this.asyncDbExecutor.supply("db.updateAsync", connection -> {
         Integer t$;
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            callback.execute(ps);
            t$ = ps.executeUpdate();
         }

         return t$;
      });
   }

   public <T> T querySyncWithResult(String sql, DbQueryService.ResultProcessor<T> processor) {
      try {
         return this.asyncDbExecutor.<T>supply("db.querySyncWithResult", connection -> {
            T x2;
            try (
               PreparedStatement ps = connection.prepareStatement(sql);
               ResultSet rs = ps.executeQuery();
            ) {
               x2 = processor.process(rs);
            }

            return x2;
         }).get();
      } catch (Exception var4) {
         this.logger.warning("Failed to execute query: " + var4.getMessage());
         return null;
      }
   }

   public <T> T querySyncWithParamsAndResult(String sql, DbQueryService.ResultParamProcessor<T> processor) {
      try {
         return this.asyncDbExecutor.<T>supply("db.querySyncWithParamsAndResult", connection -> {
            T x2;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               processor.params(ps);

               try (ResultSet rs = ps.executeQuery()) {
                  x2 = processor.process(rs);
               }
            }

            return x2;
         }).get();
      } catch (Exception var4) {
         this.logger.warning("Failed to execute query: " + var4.getMessage());
         return null;
      }
   }

   public <T> CompletableFuture<T> queryAsyncWithResult(String sql, DbQueryService.ResultParamProcessor<T> processor) {
      return this.asyncDbExecutor.<T>supply("db.queryAsyncWithResult", connection -> {
         T x2;
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            processor.params(ps);

            try (ResultSet rs = ps.executeQuery()) {
               x2 = processor.process(rs);
            }
         }

         return x2;
      });
   }

   @FunctionalInterface
   public interface ParamQueryCallback {
      void execute(PreparedStatement var1) throws SQLException;
   }

   @FunctionalInterface
   public interface QueryCallback {
      void execute(PreparedStatement var1) throws SQLException;
   }

   public interface ResultParamProcessor<T> {
      void params(PreparedStatement var1) throws SQLException;

      T process(ResultSet var1) throws SQLException;
   }

   @FunctionalInterface
   public interface ResultProcessor<T> {
      T process(ResultSet var1) throws SQLException;
   }
}


