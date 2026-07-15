package network.skypvp.paper.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import network.skypvp.paper.database.AsyncDbExecutor;
import network.skypvp.paper.model.NpcDefinition;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.core.database.DatabaseManager;

public final class NpcRepository {
   // $VF: renamed from: db network.SkyPvP.paper.persistence.DatabaseManager
   private final DatabaseManager db;
   private final Logger logger;
   private final AsyncDbExecutor asyncDbExecutor;
   private static final Gson GSON = new Gson();
   private volatile Consumer<String> mutationListener;

   public NpcRepository(DatabaseManager db, Logger logger, AsyncDbExecutor asyncDbExecutor) {
      this.db = db;
      this.logger = logger;
      this.asyncDbExecutor = asyncDbExecutor;
   }

   private <T> T executeAsync(String label, AsyncDbExecutor.SqlSupplier<T> supplier) {
      try {
         return this.asyncDbExecutor.supply(label, supplier).get();
      } catch (Exception var4) {
         this.logger.warning("[NpcRepository] " + label + " failed: " + var4.getMessage());
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
            this.logger.warning("[NpcRepository] mutation listener failed: " + var4.getMessage());
         }
      }
   }

   public List<NpcDefinition> loadAll(String serverId) {
      try {
         return this.loadAllAsync(serverId).get();
      } catch (Exception var3) {
         this.logger.warning("[NpcRepository] npc.loadAll failed: " + var3.getMessage());
         throw new RuntimeException(var3);
      }
   }

   public CompletableFuture<List<NpcDefinition>> loadAllAsync(String serverId) {
      String sql = "SELECT id, display_name, entity_type, world_name, x, y, z, yaw, pitch, action_type, action_data, skin_url, skin_signature, glow, glow_color, face_player, navigator, scale, hologram_lines, hologram_background, hologram_see_through, hologram_shadowed, hologram_alignment, hologram_view_range, hologram_freeze, hologram_billboard, hologram_scale FROM network_npcs WHERE server_id = ?";
      return this.asyncDbExecutor.supply("npc.loadAll", connection -> {
         List<NpcDefinition> rows = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, serverId);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  NpcDefinition def = new NpcDefinition();
                  def.id = rs.getString("id");
                  def.scope = serverId;
                  def.displayName = rs.getString("display_name");
                  def.entityType = rs.getString("entity_type");
                  WorldPoint pt = new WorldPoint();
                  pt.world = rs.getString("world_name");
                  pt.x = rs.getDouble("x");
                  pt.y = rs.getDouble("y");
                  pt.z = rs.getDouble("z");
                  pt.yaw = rs.getFloat("yaw");
                  pt.pitch = rs.getFloat("pitch");
                  def.location = pt;
                  def.actionType = rs.getString("action_type");
                  def.actionData = rs.getString("action_data");
                  def.skinUrl = rs.getString("skin_url");
                  def.skinSignature = rs.getString("skin_signature");
                  def.glow = rs.getBoolean("glow");
                  def.glowColor = rs.getString("glow_color");
                  def.facePlayer = rs.getBoolean("face_player");
                  def.navigator = rs.getBoolean("navigator");
                  def.scale = rs.getDouble("scale");
                  String linesJson = rs.getString("hologram_lines");
                  if (linesJson != null && !linesJson.isEmpty()) {
                     try {
                        def.hologramLines = GSON.fromJson(linesJson, (new TypeToken<List<String>>() {
                           {
                              Objects.requireNonNull(NpcRepository.this);
                           }
                        }).getType());
                     } catch (Exception var13) {
                        def.hologramLines = new ArrayList<>();
                     }
                  } else {
                     def.hologramLines = new ArrayList<>();
                  }
                  this.readHologramDisplayOptions(rs, def);

                  rows.add(def);
               }
            }
         }

         return rows;
      });
   }

   public void upsert(String serverId, NpcDefinition def, String createdBy) {
      String sql = "INSERT INTO network_npcs (id, server_id, display_name, entity_type, world_name, x, y, z, yaw, pitch, action_type, action_data, created_by, skin_url, skin_signature, glow, glow_color, face_player, navigator, scale, hologram_lines, hologram_background, hologram_see_through, hologram_shadowed, hologram_alignment, hologram_view_range, hologram_freeze, hologram_billboard, hologram_scale) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id, server_id) DO UPDATE SET display_name=EXCLUDED.display_name, entity_type=EXCLUDED.entity_type, world_name=EXCLUDED.world_name, x=EXCLUDED.x, y=EXCLUDED.y, z=EXCLUDED.z, yaw=EXCLUDED.yaw, pitch=EXCLUDED.pitch, action_type=EXCLUDED.action_type, action_data=EXCLUDED.action_data, skin_url=EXCLUDED.skin_url, skin_signature=EXCLUDED.skin_signature, glow=EXCLUDED.glow, glow_color=EXCLUDED.glow_color, face_player=EXCLUDED.face_player, navigator=EXCLUDED.navigator, scale=EXCLUDED.scale, hologram_lines=EXCLUDED.hologram_lines, hologram_background=EXCLUDED.hologram_background, hologram_see_through=EXCLUDED.hologram_see_through, hologram_shadowed=EXCLUDED.hologram_shadowed, hologram_alignment=EXCLUDED.hologram_alignment, hologram_view_range=EXCLUDED.hologram_view_range, hologram_freeze=EXCLUDED.hologram_freeze, hologram_billboard=EXCLUDED.hologram_billboard, hologram_scale=EXCLUDED.hologram_scale, created_by=EXCLUDED.created_by";

      try {
         this.executeAsync("npc.upsert", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setString(1, def.id);
               ps.setString(2, serverId);
               ps.setString(3, def.displayName);
               ps.setString(4, def.entityType);
               WorldPoint pt = def.location != null ? def.location : new WorldPoint();
               ps.setString(5, pt.world != null ? pt.world : "world");
               ps.setDouble(6, pt.x);
               ps.setDouble(7, pt.y);
               ps.setDouble(8, pt.z);
               ps.setFloat(9, pt.yaw);
               ps.setFloat(10, pt.pitch);
               ps.setString(11, def.actionType != null ? def.actionType : "NONE");
               ps.setString(12, def.actionData != null ? def.actionData : "");
               ps.setString(13, createdBy);
               ps.setString(14, def.skinUrl);
               ps.setString(15, def.skinSignature);
               ps.setBoolean(16, def.glow);
               ps.setString(17, def.glowColor);
               ps.setBoolean(18, def.facePlayer);
               ps.setBoolean(19, def.navigator);
               ps.setDouble(20, def.scale);
               ps.setString(21, def.hologramLines != null ? GSON.toJson(def.hologramLines) : "[]");
               ps.setBoolean(22, def.hologramBackground);
               ps.setBoolean(23, def.hologramSeeThrough);
               ps.setBoolean(24, def.hologramShadowed);
               ps.setString(25, def.hologramAlignment != null ? def.hologramAlignment : "CENTER");
               ps.setFloat(26, def.hologramViewRange > 0.0F ? def.hologramViewRange : 1.0F);
               ps.setBoolean(27, def.hologramFreeze);
               ps.setString(28, def.hologramBillboard != null ? def.hologramBillboard : "CENTER");
               ps.setDouble(29, def.hologramScale > 0.0D ? def.hologramScale : 1.0D);
               ps.executeUpdate();
            }

            return null;
         });
         this.notifyMutated(serverId);
      } catch (Exception var6) {
         this.logger.warning("[NpcRepository] upsert failed for " + def.id + ": " + var6.getMessage());
      }
   }

   public boolean delete(String serverId, String npcId) {
      String sql = "DELETE FROM network_npcs WHERE id = ? AND server_id = ?";

      try {
         boolean removed = this.<Boolean>executeAsync("npc.delete", connection -> {
            Boolean t$;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
               ps.setString(1, npcId);
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
         this.logger.warning("[NpcRepository] delete failed for " + npcId + ": " + var5.getMessage());
         return false;
      }
   }

   public CompletableFuture<List<NpcDefinition>> loadAllScopedAsync() {
      String sql = "SELECT server_id, id, display_name, entity_type, world_name, x, y, z, yaw, pitch, action_type, action_data, skin_url, skin_signature, glow, glow_color, face_player, navigator, scale, hologram_lines, hologram_background, hologram_see_through, hologram_shadowed, hologram_alignment, hologram_view_range, hologram_freeze, hologram_billboard, hologram_scale FROM network_npcs ORDER BY server_id, id";
      return this.asyncDbExecutor.supply("npc.loadAllScoped", connection -> {
         List<NpcDefinition> rows = new ArrayList<>();

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
      String sql = "SELECT DISTINCT server_id FROM network_npcs ORDER BY server_id";
      return this.asyncDbExecutor.supply("npc.listScopes", connection -> {
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

   public CompletableFuture<List<NpcDefinition>> findAllByIdAsync(String npcId) {
      String sql = "SELECT server_id, id, display_name, entity_type, world_name, x, y, z, yaw, pitch, action_type, action_data, skin_url, skin_signature, glow, glow_color, face_player, navigator, scale, hologram_lines, hologram_background, hologram_see_through, hologram_shadowed, hologram_alignment, hologram_view_range, hologram_freeze, hologram_billboard, hologram_scale FROM network_npcs WHERE id = ?";
      return this.asyncDbExecutor.supply("npc.findAllById", connection -> {
         List<NpcDefinition> rows = new ArrayList<>();

         try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, npcId);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  rows.add(this.readRow(rs, true));
               }
            }
         }

         return rows;
      });
   }

   public boolean changeScope(String npcId, String fromScope, String toScope) {
      if (fromScope == null || toScope == null || fromScope.equals(toScope)) {
         return fromScope != null && fromScope.equals(toScope);
      }

      try {
         boolean changed = this.<Boolean>executeAsync("npc.changeScope", connection -> {
            connection.setAutoCommit(false);

            try {
               if (!this.exists(connection, npcId, fromScope)) {
                  connection.rollback();
                  return false;
               }
               if (this.exists(connection, npcId, toScope)) {
                  connection.rollback();
                  return false;
               }

               try (PreparedStatement ps = connection.prepareStatement(
                  "UPDATE network_npcs SET server_id = ? WHERE id = ? AND server_id = ?"
               )) {
                  ps.setString(1, toScope);
                  ps.setString(2, npcId);
                  ps.setString(3, fromScope);
                  if (ps.executeUpdate() == 0) {
                     connection.rollback();
                     return false;
                  }
               }

               try (PreparedStatement holo = connection.prepareStatement(
                  "UPDATE network_holograms SET server_id = ? WHERE id = ? AND server_id = ?"
               )) {
                  holo.setString(1, toScope);
                  holo.setString(2, npcId + "_holo");
                  holo.setString(3, fromScope);
                  holo.executeUpdate();
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
         this.logger.warning("[NpcRepository] changeScope failed for " + npcId + ": " + exception.getMessage());
         return false;
      }
   }

   private boolean exists(java.sql.Connection connection, String npcId, String scope) throws Exception {
      try (PreparedStatement ps = connection.prepareStatement(
         "SELECT 1 FROM network_npcs WHERE id = ? AND server_id = ? LIMIT 1"
      )) {
         ps.setString(1, npcId);
         ps.setString(2, scope);
         try (ResultSet rs = ps.executeQuery()) {
            return rs.next();
         }
      }
   }

   private NpcDefinition readRow(ResultSet rs, boolean includeScope) throws Exception {
      NpcDefinition def = new NpcDefinition();
      if (includeScope) {
         def.scope = rs.getString("server_id");
      }
      def.id = rs.getString("id");
      def.displayName = rs.getString("display_name");
      def.entityType = rs.getString("entity_type");
      WorldPoint pt = new WorldPoint();
      pt.world = rs.getString("world_name");
      pt.x = rs.getDouble("x");
      pt.y = rs.getDouble("y");
      pt.z = rs.getDouble("z");
      pt.yaw = rs.getFloat("yaw");
      pt.pitch = rs.getFloat("pitch");
      def.location = pt;
      def.actionType = rs.getString("action_type");
      def.actionData = rs.getString("action_data");
      def.skinUrl = rs.getString("skin_url");
      def.skinSignature = rs.getString("skin_signature");
      def.glow = rs.getBoolean("glow");
      def.glowColor = rs.getString("glow_color");
      def.facePlayer = rs.getBoolean("face_player");
      def.navigator = rs.getBoolean("navigator");
      def.scale = rs.getDouble("scale");
      String linesJson = rs.getString("hologram_lines");
      if (linesJson != null && !linesJson.isEmpty()) {
         try {
            def.hologramLines = GSON.fromJson(linesJson, (new TypeToken<List<String>>() {
               {
                  Objects.requireNonNull(NpcRepository.this);
               }
            }).getType());
         } catch (Exception ignored) {
            def.hologramLines = new ArrayList<>();
         }
      } else {
         def.hologramLines = new ArrayList<>();
      }
      this.readHologramDisplayOptions(rs, def);

      return def;
   }

   private void readHologramDisplayOptions(ResultSet rs, NpcDefinition def) throws Exception {
      def.hologramBackground = rs.getBoolean("hologram_background");
      def.hologramSeeThrough = rs.getBoolean("hologram_see_through");
      def.hologramShadowed = rs.getBoolean("hologram_shadowed");
      def.hologramAlignment = rs.getString("hologram_alignment");
      if (def.hologramAlignment == null || def.hologramAlignment.isBlank()) {
         def.hologramAlignment = "CENTER";
      }
      def.hologramViewRange = rs.getFloat("hologram_view_range");
      if (rs.wasNull() || def.hologramViewRange <= 0.0F) {
         def.hologramViewRange = 1.0F;
      }
      def.hologramFreeze = rs.getBoolean("hologram_freeze");
      if (rs.wasNull()) {
         def.hologramFreeze = true;
      }
      def.hologramBillboard = rs.getString("hologram_billboard");
      if (def.hologramBillboard == null || def.hologramBillboard.isBlank()) {
         def.hologramBillboard = "CENTER";
      }
      def.hologramScale = rs.getDouble("hologram_scale");
      if (rs.wasNull() || def.hologramScale <= 0.0D) {
         def.hologramScale = 1.0D;
      }
   }
}
