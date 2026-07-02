package network.skypvp.paper.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.plugin.Plugin;

public final class AsyncDbExecutor {
   private final DatabaseManager databaseManager;
   private final Plugin plugin;
   private final ServerPlatform scheduler;
   private final Logger logger;

   public AsyncDbExecutor(DatabaseManager databaseManager, Plugin plugin, ServerPlatform scheduler, Logger logger) {
      this.databaseManager = databaseManager;
      this.plugin = plugin;
      this.scheduler = scheduler;
      this.logger = logger;
   }

   public <T> CompletableFuture<T> supply(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      CompletableFuture<T> future = new CompletableFuture<>();
      this.scheduler.runAsync(() -> {
         try (Connection connection = this.databaseManager.getConnection()) {
            future.complete(supplier.method_5(connection));
         } catch (SQLException var9) {
            this.logger.warning("[DB] " + safeLabel(label) + " failed: " + var9.getMessage());
            future.completeExceptionally(var9);
         } catch (Exception var10) {
            this.logger.warning("[DB] " + safeLabel(label) + " failed: " + var10.getMessage());
            future.completeExceptionally(var10);
         }
      });
      return future;
   }

   // $VF: renamed from: run (java.lang.String, network.SkyPvP.paper.database.AsyncDbExecutor$SqlConsumer) java.util.concurrent.CompletableFuture
   public CompletableFuture<Void> method_244(String label, AsyncDbExecutor.SqlConsumer consumer) {
      return this.supply(label, connection -> {
         consumer.accept(connection);
         return null;
      });
   }

   public <T> void handleOnMainThread(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
      future.whenComplete((result, throwable) -> this.scheduler.runGlobal(() -> {
            if (throwable != null) {
               if (onFailure != null) {
                  onFailure.accept(throwable);
               }
            } else {
               if (onSuccess != null) {
                  onSuccess.accept((T)result);
               }
            }
         }));
   }

   private static String safeLabel(String label) {
      return label != null && !label.isBlank() ? label : "query";
   }

   @FunctionalInterface
   public interface SqlConsumer {
      void accept(Connection var1) throws Exception;
   }

   @FunctionalInterface
   public interface SqlSupplier<T> {
      // $VF: renamed from: get (java.sql.Connection) java.lang.Object
      T method_5(Connection var1) throws Exception;
   }
}
