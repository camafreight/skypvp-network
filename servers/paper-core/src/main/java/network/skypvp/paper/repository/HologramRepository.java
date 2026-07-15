package network.skypvp.paper.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.model.HologramDefinition;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.core.database.DatabaseManager;

public final class HologramRepository {
   // $VF: renamed from: db network.SkyPvP.paper.persistence.DatabaseManager
   private final DatabaseManager db;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private volatile Consumer<String> mutationListener;

   public HologramRepository(DatabaseManager db, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.db = db;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get();
      } catch (Exception var4) {
         this.logger.warning("[HologramRepository] " + label + " failed: " + var4.getMessage());
         throw new RuntimeException(var4);
      }
   }

   public void setMutationListener(Consumer<String> listener) {
      this.mutationListener = listener;
   }

   private void notifyMutated(String scope) {
      Consumer<String> listener = this.mutationListener;
      if (listener != null) {
         try {
            listener.accept(scope);
         } catch (Exception var4) {
            this.logger.warning("[HologramRepository] mutation listener failed: " + var4.getMessage());
         }
      }
   }

   public List<HologramDefinition> loadAll(String serverId) {
      try {
         return this.loadAllAsync(serverId).get();
      } catch (Exception var3) {
         this.logger.warning("[HologramRepository] holo.loadAll failed: " + var3.getMessage());
         throw new RuntimeException(var3);
      }
   }

   public CompletableFuture<List<HologramDefinition>> loadAllAsync(String serverId) {
      String sql = "SELECT id, lines, interactive, hitbox_size, world_name, x, y, z, yaw, pitch, action_type, action_data, parent_id, offset_x, offset_y, offset_z, billboard, scale, background, see_through, shadowed, text_alignment, view_range, \"freeze\" FROM network_holograms WHERE server_id = ?";
      return this.asyncDbExecutor.supply("holo.loadAll", connection -> {
         List<HologramDefinition> rows = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, serverId);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  HologramDefinition def = new HologramDefinition();
                  def.id = rs.getString("id");
                  def.scope = serverId;
                  String linesText = rs.getString("lines");
                  if (linesText != null && !linesText.isEmpty()) {
                     def.lines = new ArrayList<>(Arrays.asList(linesText.split("\n")));
                  } else {
                     def.lines = new ArrayList<>();
                  }

                  def.interactive = rs.getBoolean("interactive");
                  def.hitboxSize = rs.getInt("hitbox_size");
                  WorldPoint pt = new WorldPoint();
                  pt.world = rs.getString("world_name");
                  pt.x = rs.getDouble("x");
                  pt.y = rs.getDouble("y");
                  pt.z = rs.getDouble("z");
                  pt.yaw = rs.getFloat("yaw");
                  pt.pitch = rs.getFloat("pitch");
                  def.anchor = pt;
                  def.actionType = rs.getString("action_type");
                  def.actionData = rs.getString("action_data");
                  def.parentId = rs.getString("parent_id");
                  def.offsetX = rs.getDouble("offset_x");
                  def.offsetY = rs.getDouble("offset_y");
                  def.offsetZ = rs.getDouble("offset_z");
                  def.billboard = rs.getString("billboard");
                  if (def.billboard == null) def.billboard = "CENTER";
                  def.scale = rs.getDouble("scale");
                  if (rs.wasNull()) {
                     def.scale = 1.0;
                  }
                  def.background = rs.getBoolean("background");
                  def.seeThrough = rs.getBoolean("see_through");
                  def.shadowed = rs.getBoolean("shadowed");
                  def.textAlignment = rs.getString("text_alignment");
                  if (def.textAlignment == null) {
                     def.textAlignment = "CENTER";
                  }
                  def.viewRange = rs.getFloat("view_range");
                  if (rs.wasNull() || def.viewRange <= 0.0F) {
                     def.viewRange = 1.0F;
                  }
                  def.freeze = rs.getBoolean("freeze");
                  if (rs.wasNull()) {
                     def.freeze = true;
                  }
                  rows.add(def);
               }
            }
         }

         return rows;
      });
   }

   public void upsert(String serverId, HologramDefinition def, String createdBy) {
      String sql = "INSERT INTO network_holograms (id, server_id, lines, interactive, hitbox_size, world_name, x, y, z, yaw, pitch, action_type, action_data, created_by, parent_id, offset_x, offset_y, offset_z, billboard, scale, background, see_through, shadowed, text_alignment, view_range, \"freeze\") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id, server_id) DO UPDATE SET lines=EXCLUDED.lines, interactive=EXCLUDED.interactive, hitbox_size=EXCLUDED.hitbox_size, world_name=EXCLUDED.world_name, x=EXCLUDED.x, y=EXCLUDED.y, z=EXCLUDED.z, yaw=EXCLUDED.yaw, pitch=EXCLUDED.pitch, action_type=EXCLUDED.action_type, action_data=EXCLUDED.action_data, created_by=EXCLUDED.created_by, parent_id=EXCLUDED.parent_id, offset_x=EXCLUDED.offset_x, offset_y=EXCLUDED.offset_y, offset_z=EXCLUDED.offset_z, billboard=EXCLUDED.billboard, scale=EXCLUDED.scale, background=EXCLUDED.background, see_through=EXCLUDED.see_through, shadowed=EXCLUDED.shadowed, text_alignment=EXCLUDED.text_alignment, view_range=EXCLUDED.view_range, \"freeze\"=EXCLUDED.\"freeze\"";

      try {
         this.executeAsync("holo.upsert", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setString(1, def.id);
               ps.setString(2, serverId);
               String linesText = def.lines != null ? String.join("\n", def.lines) : "";
               ps.setString(3, linesText);
               ps.setBoolean(4, def.interactive);
               ps.setInt(5, def.hitboxSize);
               WorldPoint pt = def.anchor != null ? def.anchor : new WorldPoint();
               ps.setString(6, pt.world != null ? pt.world : "world");
               ps.setDouble(7, pt.x);
               ps.setDouble(8, pt.y);
               ps.setDouble(9, pt.z);
               ps.setFloat(10, pt.yaw);
               ps.setFloat(11, pt.pitch);
               ps.setString(12, def.actionType != null ? def.actionType : "NONE");
               ps.setString(13, def.actionData != null ? def.actionData : "");
               ps.setString(14, createdBy);
               ps.setString(15, def.parentId);
               ps.setDouble(16, def.offsetX);
               ps.setDouble(17, def.offsetY);
               ps.setDouble(18, def.offsetZ);
               ps.setString(19, def.billboard != null ? def.billboard : "CENTER");
               ps.setDouble(20, def.scale);
               ps.setBoolean(21, def.background);
               ps.setBoolean(22, def.seeThrough);
               ps.setBoolean(23, def.shadowed);
               ps.setString(24, def.textAlignment != null ? def.textAlignment : "CENTER");
               ps.setFloat(25, def.viewRange > 0.0F ? def.viewRange : 1.0F);
               ps.setBoolean(26, def.freeze);
               ps.executeUpdate();
            }

            return null;
         });
         this.notifyMutated(serverId);
      } catch (Exception var6) {
         this.logger.warning("[HologramRepository] upsert failed for " + def.id + ": " + var6.getMessage());
      }
   }

   public boolean delete(String serverId, String hologramId) {
      String sql = "DELETE FROM network_holograms WHERE id = ? AND server_id = ?";

      try {
         boolean removed = this.<Boolean>executeAsync("holo.delete", connection -> {
            Boolean t$;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setString(1, hologramId);
               ps.setString(2, serverId);
               t$ = ps.executeUpdate() > 0;
            }

            return t$;
         });
         if (removed) {
            this.notifyMutated(serverId);
         }

         return removed;
      } catch (Exception var5) {
         this.logger.warning("[HologramRepository] delete failed for " + hologramId + ": " + var5.getMessage());
         throw new RuntimeException("Delete failed", var5);
      }
   }

   public CompletableFuture<List<HologramDefinition>> loadAllScopedAsync() {
      String sql = "SELECT server_id, id, lines, interactive, hitbox_size, world_name, x, y, z, yaw, pitch, action_type, action_data, parent_id, offset_x, offset_y, offset_z, billboard, scale, background, see_through, shadowed, text_alignment, view_range, \"freeze\" FROM network_holograms ORDER BY server_id, id";
      return this.asyncDbExecutor.supply("holo.loadAllScoped", connection -> {
         List<HologramDefinition> rows = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  rows.add(this.readRow(rs, true));
               }
            }
         }

         return rows;
      });
   }

   public CompletableFuture<List<String>> listScopesAsync() {
      String sql = "SELECT DISTINCT server_id FROM network_holograms ORDER BY server_id";
      return this.asyncDbExecutor.supply("holo.listScopes", connection -> {
         List<String> scopes = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  scopes.add(rs.getString("server_id"));
               }
            }
         }

         return scopes;
      });
   }

   public CompletableFuture<List<HologramDefinition>> findAllByIdAsync(String hologramId) {
      String sql = "SELECT server_id, id, lines, interactive, hitbox_size, world_name, x, y, z, yaw, pitch, action_type, action_data, parent_id, offset_x, offset_y, offset_z, billboard, scale, background, see_through, shadowed, text_alignment, view_range, \"freeze\" FROM network_holograms WHERE id = ?";
      return this.asyncDbExecutor.supply("holo.findAllById", connection -> {
         List<HologramDefinition> rows = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, hologramId);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  rows.add(this.readRow(rs, true));
               }
            }
         }

         return rows;
      });
   }

   public boolean changeScope(String hologramId, String fromScope, String toScope) {
      if (fromScope == null || toScope == null || fromScope.equals(toScope)) {
         return fromScope != null && fromScope.equals(toScope);
      }

      try {
         boolean changed = this.<Boolean>executeAsync("holo.changeScope", connection -> {
            List<HologramDefinition> scoped = this.loadAll(connection, fromScope);
            Set<String> idsToMove = new HashSet<>();
            this.collectSubtree(hologramId, scoped, idsToMove);
            if (idsToMove.isEmpty()) {
               return false;
            }

            for (String id : idsToMove) {
               if (this.exists(connection, id, toScope)) {
                  return false;
               }
            }

            connection.setAutoCommit(false);

            try {
               for (String id : idsToMove) {
                  try (PreparedStatement ps = connection.prepareStatement(
                     "UPDATE network_holograms SET server_id = ? WHERE id = ? AND server_id = ?"
                  )) {
                     ps.setString(1, toScope);
                     ps.setString(2, id);
                     ps.setString(3, fromScope);
                     if (ps.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                     }
                  }
               }

               connection.commit();
               return true;
            } catch (Exception exception) {
               connection.rollback();
               throw exception;
            } finally {
               connection.setAutoCommit(true);
            }
         });
         if (changed) {
            this.notifyMutated(fromScope);
            this.notifyMutated(toScope);
         }

         return changed;
      } catch (Exception exception) {
         this.logger.warning("[HologramRepository] changeScope failed for " + hologramId + ": " + exception.getMessage());
         return false;
      }
   }

   private List<HologramDefinition> loadAll(java.sql.Connection connection, String serverId) throws Exception {
      String sql = "SELECT server_id, id, lines, interactive, hitbox_size, world_name, x, y, z, yaw, pitch, action_type, action_data, parent_id, offset_x, offset_y, offset_z, billboard, scale, background, see_through, shadowed, text_alignment, view_range, \"freeze\" FROM network_holograms WHERE server_id = ?";
      List<HologramDefinition> rows = new ArrayList<>();

      try (PreparedStatement ps = connection.prepareStatement(sql)) {
         ps.setString(1, serverId);

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               rows.add(this.readRow(rs, true));
            }
         }
      }

      return rows;
   }

   private void collectSubtree(String rootId, List<HologramDefinition> scoped, Set<String> idsToMove) {
      for (HologramDefinition def : scoped) {
         if (rootId.equalsIgnoreCase(def.id)) {
            idsToMove.add(def.id);
            for (HologramDefinition child : scoped) {
               if (def.id.equalsIgnoreCase(child.parentId)) {
                  this.collectSubtree(child.id, scoped, idsToMove);
               }
            }
            return;
         }
      }
   }

   private boolean exists(java.sql.Connection connection, String hologramId, String scope) throws Exception {
      try (PreparedStatement ps = connection.prepareStatement(
         "SELECT 1 FROM network_holograms WHERE id = ? AND server_id = ? LIMIT 1"
      )) {
         ps.setString(1, hologramId);
         ps.setString(2, scope);
         try (ResultSet rs = ps.executeQuery()) {
            return rs.next();
         }
      }
   }

   private HologramDefinition readRow(ResultSet rs, boolean includeScope) throws Exception {
      HologramDefinition def = new HologramDefinition();
      if (includeScope) {
         def.scope = rs.getString("server_id");
      }
      def.id = rs.getString("id");
      String linesText = rs.getString("lines");
      if (linesText != null && !linesText.isEmpty()) {
         def.lines = new ArrayList<>(Arrays.asList(linesText.split("\n")));
      } else {
         def.lines = new ArrayList<>();
      }

      def.interactive = rs.getBoolean("interactive");
      def.hitboxSize = rs.getInt("hitbox_size");
      WorldPoint pt = new WorldPoint();
      pt.world = rs.getString("world_name");
      pt.x = rs.getDouble("x");
      pt.y = rs.getDouble("y");
      pt.z = rs.getDouble("z");
      pt.yaw = rs.getFloat("yaw");
      pt.pitch = rs.getFloat("pitch");
      def.anchor = pt;
      def.actionType = rs.getString("action_type");
      def.actionData = rs.getString("action_data");
      def.parentId = rs.getString("parent_id");
      def.offsetX = rs.getDouble("offset_x");
      def.offsetY = rs.getDouble("offset_y");
      def.offsetZ = rs.getDouble("offset_z");
      def.billboard = rs.getString("billboard");
      if (def.billboard == null) {
         def.billboard = "CENTER";
      }
      def.scale = rs.getDouble("scale");
      if (rs.wasNull()) {
         def.scale = 1.0;
      }
      def.background = rs.getBoolean("background");
      def.seeThrough = rs.getBoolean("see_through");
      def.shadowed = rs.getBoolean("shadowed");
      def.textAlignment = rs.getString("text_alignment");
      if (def.textAlignment == null) {
         def.textAlignment = "CENTER";
      }
      def.viewRange = rs.getFloat("view_range");
      if (rs.wasNull() || def.viewRange <= 0.0F) {
         def.viewRange = 1.0F;
      }
      def.freeze = rs.getBoolean("freeze");
      if (rs.wasNull()) {
         def.freeze = true;
      }
      return def;
   }
}
