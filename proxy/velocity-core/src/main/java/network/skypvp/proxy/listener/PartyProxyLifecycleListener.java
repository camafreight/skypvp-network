package network.skypvp.proxy.listener;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.proxy.service.PartyMemberMover;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.PartyTransferGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PartyProxyLifecycleListener {
   private static final Logger logger = LoggerFactory.getLogger(PartyProxyLifecycleListener.class);
   private final ProxyServer proxyServer;
   private final PartyService partyService;
   private final PartyTransferGate transferGate;

   public PartyProxyLifecycleListener(ProxyServer proxyServer, PartyService partyService, PartyTransferGate transferGate) {
      this.proxyServer = proxyServer;
      this.partyService = partyService;
      this.transferGate = transferGate;
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      if (this.transferGate != null) {
         this.transferGate.clear(event.getPlayer().getUniqueId());
      }
      if (this.partyService != null) {
         Player disconnectingPlayer = event.getPlayer();
         UUID playerId = disconnectingPlayer.getUniqueId();
         String username = disconnectingPlayer.getUsername();
         Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(playerId);
         if (!partyOpt.isEmpty()) {
            PartyService.PartyState party = partyOpt.get();
            boolean wasLeader = party.leaderId().equals(playerId);
            int memberCountBefore = party.members().size();
            PartyService.PartyActionResult result = this.partyService.leave(playerId);
            if (!result.success()) {
               logger.debug("Player '{}' leave failed: {}", username, result.message());
            } else if (result.party() == null) {
               logger.info("Party disbanded: '{}' was {}the only member (party had {} members)", username, wasLeader ? "leader and " : "", memberCountBefore);
            } else {
               PartyService.PartyState updatedParty = result.party();
               if (wasLeader) {
                  Optional<Player> newLeader = this.proxyServer.getPlayer(updatedParty.leaderId());
                  String newLeaderName = newLeader.<String>map(Player::getUsername).orElse("Unknown");
                  logger.info("Party leadership transferred: '{}' (leader) left party, leadership passed to '{}'", username, newLeaderName);

                  for (UUID memberId : updatedParty.members()) {
                     Optional<Player> memberOpt = this.proxyServer.getPlayer(memberId);
                     if (!memberOpt.isEmpty()) {
                        Player member = memberOpt.get();
                        if (memberId.equals(updatedParty.leaderId())) {
                           member.sendMessage(ServerTextUtil.component("&e" + "You are now the party leader (previous leader " + username + " left)."));
                        } else {
                           member.sendMessage(
                              ServerTextUtil.component("&b" + "Party leader changed: " + newLeaderName + " is now leading. (" + username + " left)")
                           );
                        }
                     }
                  }
               } else {
                  logger.info(
                     "Player '{}' left party (leader: {})",
                     username,
                     this.proxyServer.getPlayer(updatedParty.leaderId()).<String>map(Player::getUsername).orElse("Unknown")
                  );
                  Optional<Player> leader = this.proxyServer.getPlayer(updatedParty.leaderId());
                  String leaderName = leader.<String>map(Player::getUsername).orElse("Unknown");

                  for (UUID memberIdx : updatedParty.members()) {
                     if (!memberIdx.equals(playerId)) {
                        Optional<Player> memberOpt = this.proxyServer.getPlayer(memberIdx);
                        if (!memberOpt.isEmpty()) {
                           memberOpt.get().sendMessage(ServerTextUtil.component("&7" + username + " left the party."));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @Subscribe
   public void onServerConnected(ServerConnectedEvent event) {
   }
}
