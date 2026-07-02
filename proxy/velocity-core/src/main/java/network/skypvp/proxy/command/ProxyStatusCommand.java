package network.skypvp.proxy.command;

import network.skypvp.shared.ServerTextUtil;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.SimpleCommand.Invocation;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.ProxyBootstrap;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import network.skypvp.proxy.registry.NetworkStateRegistry;
import network.skypvp.proxy.service.AdmissionControlService;
import network.skypvp.proxy.service.PartyQueueService;
import network.skypvp.proxy.service.PartyService;
import network.skypvp.proxy.service.QueueDrainService;
import network.skypvp.proxy.service.QueueService;
import network.skypvp.shared.FieldValueFormatter;
import network.skypvp.shared.ServerHeartbeatEvent;

public final class ProxyStatusCommand implements SimpleCommand {
   // $VF: renamed from: MM net.kyori.adventure.text.minimessage.MiniMessage
   private final ProxyBootstrap bootstrap;
   private final NetworkStateRegistry networkStateRegistry;
   private final QueueService queueService;
   private final QueueDrainService queueDrainService;
   private final PartyService partyService;
   private final PartyQueueService partyQueueService;

   public ProxyStatusCommand(
      ProxyBootstrap bootstrap,
      NetworkStateRegistry networkStateRegistry,
      QueueService queueService,
      QueueDrainService queueDrainService,
      PartyService partyService,
      PartyQueueService partyQueueService
   ) {
      this.bootstrap = bootstrap;
      this.networkStateRegistry = networkStateRegistry;
      this.queueService = queueService;
      this.queueDrainService = queueDrainService;
      this.partyService = partyService;
      this.partyQueueService = partyQueueService;
   }

   public void execute(Invocation invocation) {
      ProxyBootstrapConfig config = this.bootstrap.config();
      int registeredServers = this.bootstrap.proxyServer().getAllServers().size();
      int onlinePlayers = this.bootstrap.proxyServer().getPlayerCount();
      String queueSummary = this.queueService
         .queueSizes()
         .entrySet()
         .stream()
         .map(entry -> entry.getKey() + "=" + entry.getValue())
         .collect(Collectors.joining(", "));
      if (queueSummary.isBlank()) {
         queueSummary = "none";
      }

      String topServers = this.networkStateRegistry.topLoadedServers(3).stream().map(ProxyStatusCommand::formatServerLoad).collect(Collectors.joining(", "));
      if (topServers.isBlank()) {
         topServers = "none";
      }

      String lobbyStatus = this.networkStateRegistry.knownHeartbeats().stream()
         .filter(hb -> hb.role() != null && "LOBBY".equalsIgnoreCase(hb.role().name()))
         .map(hb -> hb.serverId() + (hb.joinable() ? "(READY)" : "(BOOT)"))
         .collect(Collectors.joining(", "));
      if (lobbyStatus.isBlank()) {
         lobbyStatus = "none";
      }

      AdmissionControlService.AdmissionSnapshot admission = this.queueDrainService.admissionSnapshot();
      PartyQueueService.PartyQueueMetrics groupMetrics = this.partyQueueService == null
         ? new PartyQueueService.PartyQueueMetrics(0, 0, 0L, 0L, 0L, 0L, 0L, 0L)
         : this.partyQueueService.metricsSnapshot();
      int partyCount = this.partyService == null ? 0 : this.partyService.activePartyCount();
      int partyMembers = this.partyService == null ? 0 : this.partyService.activePartyMemberCount();
      int pendingInvites = this.partyService == null ? 0 : this.partyService.pendingInviteCount();
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("<#FFB300><bold>[Proxy Status]</bold><reset>"));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Network", config.networkName)));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Fallback", config.fallbackServer)));
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Registered Backends", String.valueOf(config.backendServers.size())))
         );
      invocation.source()
         .sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Velocity Known Servers", String.valueOf(registeredServers))));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Online Players", String.valueOf(onlinePlayers))));
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent(
               "  " + FieldValueFormatter.fieldValueMiniMessage("Active Sessions", String.valueOf(this.networkStateRegistry.activeSessionCount()))
            )
         );
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent(
               "  " + FieldValueFormatter.fieldValueMiniMessage("Heartbeat Servers", String.valueOf(this.networkStateRegistry.trackedServerCount()))
            )
         );
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Heartbeat Online", String.valueOf(this.networkStateRegistry.totalHeartbeatOnlinePlayers()))));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Lobby Readiness", lobbyStatus)));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Queued Players", String.valueOf(this.queueService.totalQueuedPlayers()))));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Queues", queueSummary)));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Parties", String.valueOf(partyCount))));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Party Members", String.valueOf(partyMembers))));
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Party Invites", String.valueOf(pendingInvites))));
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent(
               "  "
                  + FieldValueFormatter.fieldValueMiniMessage(
                     "Group Queue", groupMetrics.activeGroups() + " groups/" + groupMetrics.queuedMembers() + " members"
                  )
            )
         );
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Group Blocked Capacity", String.valueOf(groupMetrics.blockedCapacity())))
         );
      invocation.source()
         .sendMessage(
            ServerTextUtil.miniMessageComponent(
               "  "
                  + FieldValueFormatter.fieldValueMiniMessage(
                     "Admission",
                     (admission.enabled() ? "on" : "off")
                        + ", "
                        + admission.transfersPerSecond()
                        + "/s, burst="
                        + admission.burstCapacity()
                        + ", maxPass="
                        + this.queueDrainService.maxTransfersPerDrainPass()
                  )
            )
         );
      invocation.source().sendMessage(ServerTextUtil.miniMessageComponent("  " + FieldValueFormatter.fieldValueMiniMessage("Top Servers", topServers)));
   }

   private static String formatServerLoad(ServerHeartbeatEvent event) {
      return event.serverId() + "(" + event.onlinePlayers() + "/" + event.maxPlayers() + ")";
   }
}
