package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.proxy.ProxyServer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.repository.PunishmentRepository;
import network.skypvp.shared.PunishmentRecord;

public final class BanLoginListener {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d yyyy").withZone(ZoneId.systemDefault());
   private final ProxyServer proxyServer;
   private final PunishmentRepository punishments;

   public BanLoginListener(ProxyServer proxyServer, PunishmentRepository punishments) {
      this.proxyServer = proxyServer;
      this.punishments = punishments;
   }

   @Subscribe
   public void onPreLogin(PreLoginEvent event) {
      String username = event.getUsername();
      UUID uuid = this.proxyServer.getPlayer(username).map(p -> p.getUniqueId()).or(() -> this.punishments.resolvePlayerUuid(username)).orElse(null);
      if (uuid != null) {
         Optional<PunishmentRecord> ban = this.punishments.findActivePunishment(uuid, PunishmentRecord.PunishmentType.BAN);
         if (!ban.isEmpty()) {
            PunishmentRecord record = ban.get();
            String expiry = record.isPermanent() ? "Permanent" : "Until " + DATE_FMT.format(record.expiresAt());
            Component message = ServerTextUtil.miniMessageComponent(
               "\n<#CC0000><bold>\ud83d\udeab You are banned from SkyPvP</bold><reset>\n\n<#888888>Reason: <reset><#FFFFFF>"
                  + sanitize(record.reason())
                  + "<reset>\n<#888888>Duration: <reset><#FFD700>"
                  + expiry
                  + "<reset>\n\n<#555555>───────────────────<reset>\n<#888888>Appeal: <reset><#55FF55>discord.skypvp.gg<reset>\n"
            );
            event.setResult(PreLoginComponentResult.denied(message));
         }
      }
   }

   private static String sanitize(String input) {
      return input == null ? "No reason provided" : input.replace("<", "\\<").replace(">", "\\>");
   }
}

