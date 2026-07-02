package network.skypvp.paper.service;

import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.RankRecord;
import network.skypvp.shared.BrandStyle;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.cacheddata.CachedMetaData;

public final class RankService {
   private final PaperCorePlugin plugin;
   private final Logger logger;

   public RankService(PaperCorePlugin plugin, Logger logger) {
      this.plugin = plugin;
      this.logger = logger;
   }

   public RankRecord fetchRank(UUID playerId) {
      return this.resolveLuckPermsRank(playerId);
   }

   public void loadAndCache(UUID playerId, String username) {
   }

   public RankRecord getCached(UUID playerId) {
      return this.resolveLuckPermsRank(playerId);
   }

   public void updateRank(UUID playerId, String username, String rankKey, Instant expiresAt) {
      this.logger.warning("Attempted custom updateRank for " + username + " -> " + rankKey + ". Please use LuckPerms directly.");
   }

   public void evict(UUID playerId) {
   }

   public void evictAndReload(UUID playerId) {
   }

   public void reconcileOnlinePlayers() {
   }

   private RankRecord resolveLuckPermsRank(UUID playerId) {
      try {
         LuckPerms api = LuckPermsProvider.get();
         User user = api.getUserManager().getUser(playerId);
         if (user != null) {
            String primaryGroup = user.getPrimaryGroup();
            CachedMetaData metaData = user.getCachedData().getMetaData();
            String prefix = metaData.getPrefix();
            if (prefix == null) {
               prefix = "";
            }
            
            String chatColor = BrandStyle.chatMessageColor(primaryGroup);
            int priority = 0;
            if ("owner".equalsIgnoreCase(primaryGroup) || "founder".equalsIgnoreCase(primaryGroup)) {
               priority = 1000;
            } else if ("admin".equalsIgnoreCase(primaryGroup)) {
               priority = 900;
            } else if ("staff".equalsIgnoreCase(primaryGroup) || "mod".equalsIgnoreCase(primaryGroup) || "moderator".equalsIgnoreCase(primaryGroup)) {
               priority = 500;
            } else if ("helper".equalsIgnoreCase(primaryGroup)) {
               priority = 480;
            } else if ("legend".equalsIgnoreCase(primaryGroup)) {
               priority = 450;
            } else if ("mvp+".equalsIgnoreCase(primaryGroup) || "mvp++".equalsIgnoreCase(primaryGroup)) {
               priority = 400;
            } else if ("mvp".equalsIgnoreCase(primaryGroup)) {
               priority = 300;
            } else if ("vip+".equalsIgnoreCase(primaryGroup)) {
               priority = 200;
            } else if ("vip".equalsIgnoreCase(primaryGroup)) {
               priority = 100;
            }
            
            String displayName = primaryGroup.substring(0, 1).toUpperCase() + primaryGroup.substring(1).toLowerCase();
            return new RankRecord(primaryGroup, displayName, prefix, chatColor, priority);
         }
      } catch (Exception ignored) {
      }
      return RankRecord.DEFAULT;
   }
}
