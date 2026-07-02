package network.skypvp.paper.integration;



import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import network.skypvp.paper.PaperCorePlugin;

import org.bukkit.OfflinePlayer;

import org.jetbrains.annotations.NotNull;



public final class SkyPvPPlaceholderExpansion extends PlaceholderExpansion {

   private final PaperCorePlugin plugin;



   public SkyPvPPlaceholderExpansion(PaperCorePlugin plugin) {

      this.plugin = plugin;

   }



   @Override

   public @NotNull String getIdentifier() {

      return "skypvp";

   }



   @Override

   public @NotNull String getAuthor() {

      return "SkyPvP";

   }



   @Override

   public @NotNull String getVersion() {

      return this.plugin.getDescription().getVersion();

   }



   @Override

   public boolean persist() {

      return true;

   }



   @Override

   public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {

      return SkyPvPPlaceholderSupport.resolveSkyPvP(this.plugin, offlinePlayer, params);

   }

}

