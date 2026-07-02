package network.skypvp.paper.service;

import java.util.Locale;
import java.util.UUID;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.RankRecord;
import org.bukkit.entity.Player;

public final class PremiumRankBenefitService {
   private static final int AUCTION_LISTING_HARD_CAP = 8;
   private final PaperCorePlugin plugin;

   public PremiumRankBenefitService(PaperCorePlugin plugin) {
      this.plugin = plugin;
   }

   public PremiumRankBenefitService.BenefitProfile profileFor(Player player) {
      return player == null ? PremiumRankBenefitService.BenefitProfile.NONE : this.profileFor(player.getUniqueId());
   }

   public PremiumRankBenefitService.BenefitProfile profileFor(UUID playerId) {
      if (playerId == null) {
         return PremiumRankBenefitService.BenefitProfile.NONE;
      } else {
         RankRecord rank = this.plugin.rankService() != null ? this.plugin.rankService().getCached(playerId) : RankRecord.DEFAULT;
         return this.resolve(rank);
      }
   }

   public long adjustedWarpCost(Player player, long baseCost) {
      return applyDiscount(baseCost, this.profileFor(player).warpDiscountPercent());
   }

   public long adjustedRepairCost(Player player, long baseCost) {
      return applyDiscount(baseCost, this.profileFor(player).repairDiscountPercent());
   }

   public int adjustedAuctionListingCap(Player player, int baseCap) {
      int safeBase = Math.max(1, baseCap);
      int hardCap = Math.max(safeBase, AUCTION_LISTING_HARD_CAP);
      int bonus = Math.max(0, this.profileFor(player).auctionSlotBonus());
      return Math.min(hardCap, safeBase + bonus);
   }

   private PremiumRankBenefitService.BenefitProfile resolve(RankRecord rank) {
      String profileKey = this.profileKeyFor(rank);
      if ("default".equals(profileKey)) {
         return PremiumRankBenefitService.BenefitProfile.NONE;
      } else {
         return new PremiumRankBenefitService.BenefitProfile(
            profileKey,
            clampPercent(defaultWarpDiscount(profileKey)),
            clampPercent(defaultRepairDiscount(profileKey)),
            Math.max(0, defaultAuctionSlotBonus(profileKey))
         );
      }
   }

   private String profileKeyFor(RankRecord rank) {
      if (rank != null && rank.rankKey() != null) {
         String normalized = rank.rankKey().trim().toLowerCase(Locale.ROOT);

         return switch (normalized) {
            case "vip", "vip+", "mvp", "mvp+", "legend" -> normalized;
            case "staff", "admin", "owner" -> "legend";
            default -> "default";
         };
      } else {
         return "default";
      }
   }

   private static int clampPercent(int raw) {
      return Math.max(0, Math.min(90, raw));
   }

   private static long applyDiscount(long baseCost, int discountPercent) {
      if (baseCost > 0L && discountPercent > 0) {
         long discounted = Math.round((double)baseCost * (100.0 - (double)discountPercent) / 100.0);
         return Math.max(1L, discounted);
      } else {
         return Math.max(0L, baseCost);
      }
   }

   private static int defaultWarpDiscount(String profileKey) {
      return switch (profileKey) {
         case "vip" -> 10;
         case "vip+" -> 15;
         case "mvp" -> 20;
         case "mvp+" -> 25;
         case "legend" -> 35;
         default -> 0;
      };
   }

   private static int defaultRepairDiscount(String profileKey) {
      return switch (profileKey) {
         case "vip", "vip+" -> 10;
         case "mvp" -> 15;
         case "mvp+" -> 20;
         case "legend" -> 25;
         default -> 0;
      };
   }

   private static int defaultAuctionSlotBonus(String profileKey) {
      return switch (profileKey) {
         case "mvp" -> 1;
         case "mvp+" -> 2;
         case "legend" -> 3;
         default -> 0;
      };
   }

   public static record BenefitProfile(String profileKey, int warpDiscountPercent, int repairDiscountPercent, int auctionSlotBonus) {
      static final PremiumRankBenefitService.BenefitProfile NONE = new PremiumRankBenefitService.BenefitProfile("default", 0, 0, 0);
   }
}
