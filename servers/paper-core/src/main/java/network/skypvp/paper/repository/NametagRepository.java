package network.skypvp.paper.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import network.skypvp.core.database.DatabaseManager;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.model.NametagDefinition;
import org.bukkit.plugin.Plugin;

public final class NametagRepository {

   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Type CACHE_TYPE = new TypeToken<List<NametagDefinition>>() {
   }.getType();
   private static final String CACHE_FILE = "cache/nametag-definitions.json";

   private final Plugin plugin;
   private final DatabaseManager db;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private volatile Consumer<String> mutationListener;

   public NametagRepository(Plugin plugin, DatabaseManager db, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.plugin = plugin;
      this.db = db;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   public void setMutationListener(Consumer<String> listener) {
      this.mutationListener = listener;
   }

   private void notifyMutated(String scope) {
      Consumer<String> listener = this.mutationListener;
      if (listener != null) {
         try {
            listener.accept(scope);
         } catch (Exception exception) {
            this.logger.warning("[NametagRepository] mutation listener failed: " + exception.getMessage());
         }
      }
   }

   public CompletableFuture<List<NametagDefinition>> loadAllAsync() {
      String sql = """
         SELECT scope, enabled, apply_scopes, lines, base_height, line_spacing, scale, refresh_ticks,
                hide_vanilla_name, visible_to_self, background
         FROM network_nametags
         ORDER BY scope
         """;
      return this.asyncDbExecutor.supply("nametag.loadAll", connection -> {
         List<NametagDefinition> rows = new ArrayList<>();
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  rows.add(this.readRow(rs));
               }
            }
         }
         return rows;
      });
   }

   public Optional<NametagDefinition> load(String storageScope) {
      try {
         return this.loadAsync(storageScope).get();
      } catch (Exception exception) {
         this.logger.warning("[NametagRepository] nametag.load failed for " + storageScope + ": " + exception.getMessage());
         throw new RuntimeException(exception);
      }
   }

   public CompletableFuture<Optional<NametagDefinition>> loadAsync(String storageScope) {
      String sql = """
         SELECT scope, enabled, apply_scopes, lines, base_height, line_spacing, scale, refresh_ticks,
                hide_vanilla_name, visible_to_self, background
         FROM network_nametags
         WHERE scope = ?
         """;
      return this.asyncDbExecutor.supply("nametag.load", connection -> {
         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, storageScope);
            try (ResultSet rs = ps.executeQuery()) {
               if (rs.next()) {
                  return Optional.of(this.readRow(rs));
               }
            }
         }
         return Optional.empty();
      });
   }

   public void upsert(String storageScope, NametagDefinition def, String updatedBy) {
      if (storageScope == null || storageScope.isBlank() || def == null) {
         return;
      }
      String sql = """
         INSERT INTO network_nametags (
            scope, enabled, apply_scopes, lines, base_height, line_spacing, scale, refresh_ticks,
            hide_vanilla_name, visible_to_self, background, created_by, updated_at
         ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, now())
         ON CONFLICT (scope) DO UPDATE SET
            enabled = EXCLUDED.enabled,
            apply_scopes = EXCLUDED.apply_scopes,
            lines = EXCLUDED.lines,
            base_height = EXCLUDED.base_height,
            line_spacing = EXCLUDED.line_spacing,
            scale = EXCLUDED.scale,
            refresh_ticks = EXCLUDED.refresh_ticks,
            hide_vanilla_name = EXCLUDED.hide_vanilla_name,
            visible_to_self = EXCLUDED.visible_to_self,
            background = EXCLUDED.background,
            created_by = EXCLUDED.created_by,
            updated_at = now()
         """;

      try {
         this.asyncDbExecutor.supply("nametag.upsert", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setString(1, storageScope);
               ps.setBoolean(2, def.enabled);
               ps.setString(3, encodeApplyScopes(def.scopes));
               ps.setString(4, encodeLines(def.lines));
               ps.setDouble(5, def.baseHeight);
               ps.setDouble(6, def.lineSpacing);
               ps.setFloat(7, def.scale);
               ps.setInt(8, def.refreshTicks);
               ps.setBoolean(9, def.hideVanillaName);
               ps.setBoolean(10, def.visibleToSelf);
               ps.setBoolean(11, def.background);
               ps.setString(12, updatedBy == null || updatedBy.isBlank() ? "system" : updatedBy);
               ps.executeUpdate();
            }
            return null;
         }).get();
         def.scope = storageScope;
         this.notifyMutated(storageScope);
      } catch (Exception exception) {
         this.logger.warning("[NametagRepository] upsert failed for " + storageScope + ": " + exception.getMessage());
      }
   }

   /**
    * Picks the layout that should render on {@code serverScope}: a row whose apply-scopes include the
    * server scope wins over a {@code global} row.
    */
   public static NametagDefinition resolveActive(List<NametagDefinition> definitions, String serverScope) {
      if (definitions == null || definitions.isEmpty()) {
         return new NametagDefinition();
      }
      String normalizedServer = serverScope == null ? "" : serverScope.toLowerCase(Locale.ROOT).trim();
      NametagDefinition globalMatch = null;
      NametagDefinition specificMatch = null;

      for (NametagDefinition def : definitions) {
         if (def == null || !def.enabled) {
            continue;
         }
         List<String> applyScopes = def.scopes;
         if (applyScopes == null || applyScopes.isEmpty()) {
            globalMatch = def;
            continue;
         }
         for (String applyScope : applyScopes) {
            if (applyScope == null || applyScope.isBlank()) {
               continue;
            }
            String normalized = applyScope.toLowerCase(Locale.ROOT).trim();
            if ("global".equals(normalized)) {
               globalMatch = def;
            } else if (normalized.equals(normalizedServer)) {
               specificMatch = def;
            }
         }
      }

      NametagDefinition chosen = specificMatch != null ? specificMatch : globalMatch;
      return chosen == null ? new NametagDefinition() : chosen.copy();
   }

   public void writeLocalCache(List<NametagDefinition> definitions) {
      try {
         File file = this.cacheFile();
         File parent = file.getParentFile();
         if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create cache directory");
         }
         Files.writeString(file.toPath(), GSON.toJson(definitions == null ? List.of() : definitions), StandardCharsets.UTF_8);
      } catch (IOException | RuntimeException exception) {
         this.logger.warning("[NametagRepository] Failed to write local cache: " + exception.getMessage());
      }
   }

   public List<NametagDefinition> readLocalCache() {
      File file = this.cacheFile();
      if (!file.exists()) {
         return List.of();
      }
      try {
         String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
         List<NametagDefinition> loaded = GSON.fromJson(json, CACHE_TYPE);
         return loaded == null ? List.of() : loaded;
      } catch (IOException | RuntimeException exception) {
         this.logger.warning("[NametagRepository] Failed to read local cache: " + exception.getMessage());
         return List.of();
      }
   }

   private File cacheFile() {
      return new File(this.plugin.getDataFolder(), CACHE_FILE);
   }

   private NametagDefinition readRow(ResultSet rs) throws Exception {
      NametagDefinition def = new NametagDefinition();
      def.scope = rs.getString("scope");
      def.enabled = rs.getBoolean("enabled");
      def.scopes = decodeApplyScopes(rs.getString("apply_scopes"));
      def.lines = decodeLines(rs.getString("lines"));
      def.baseHeight = rs.getDouble("base_height");
      def.lineSpacing = rs.getDouble("line_spacing");
      def.scale = rs.getFloat("scale");
      def.refreshTicks = rs.getInt("refresh_ticks");
      def.hideVanillaName = rs.getBoolean("hide_vanilla_name");
      def.visibleToSelf = rs.getBoolean("visible_to_self");
      def.background = rs.getBoolean("background");
      return def;
   }

   private static String encodeApplyScopes(List<String> scopes) {
      if (scopes == null || scopes.isEmpty()) {
         return "global";
      }
      return String.join(",", scopes);
   }

   private static List<String> decodeApplyScopes(String raw) {
      if (raw == null || raw.isBlank()) {
         return new ArrayList<>(List.of("global"));
      }
      List<String> scopes = new ArrayList<>();
      for (String part : raw.split(",")) {
         if (part != null && !part.isBlank()) {
            scopes.add(part.trim().toLowerCase(Locale.ROOT));
         }
      }
      return scopes.isEmpty() ? new ArrayList<>(List.of("global")) : scopes;
   }

   private static String encodeLines(List<String> lines) {
      return lines == null ? "" : String.join("\n", lines);
   }

   private static List<String> decodeLines(String raw) {
      if (raw == null || raw.isEmpty()) {
         return new ArrayList<>();
      }
      return new ArrayList<>(Arrays.asList(raw.split("\n", -1)));
   }
}
