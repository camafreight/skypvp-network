package network.skypvp.proxy.service;

import network.skypvp.shared.ServerTextUtil;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import network.skypvp.shared.BreachPartyDeployCancelEvent;
import network.skypvp.shared.BreachPartyQueueDeployEvent;
import network.skypvp.shared.NetworkChannels;
import network.skypvp.shared.RedisEventPublisher;
import org.slf4j.Logger;

/**
 * Cross-pod breach matchmaking for {@code /breach play}: picks a joinable instance from the network heartbeat pool,
 * publishes a deploy reservation, and routes the party to the hosting extraction pod.
 *
 * <p>Pending deploys are tracked briefly so a disconnect or party leave mid-transfer cancels the whole squad's join
 * (releases extraction reservations and stops late force-admit into the raid).</p>
 */
public final class BreachPlayMatchmakingService {

    private static final MinecraftChannelIdentifier ROUTE_CHANNEL = MinecraftChannelIdentifier.from("skypvp:route");
    private static final long PENDING_DEPLOY_TTL_MILLIS = 60_000L;

    private static final Sound SOUND_SEARCHING = Sound.sound(Key.key("minecraft", "block.note_block.hat"), Sound.Source.MASTER, 0.7F, 1.35F);
    private static final Sound SOUND_DEPLOY = Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 0.85F, 1.2F);
    private static final Sound SOUND_FAIL = Sound.sound(Key.key("minecraft", "block.note_block.bass"), Sound.Source.MASTER, 0.9F, 0.65F);
    private static final Sound SOUND_CANCEL = Sound.sound(Key.key("minecraft", "block.note_block.bass"), Sound.Source.MASTER, 0.85F, 0.55F);

    private final ProxyServer proxyServer;
    private final ServerRoutingService routingService;
    private final PartyService partyService;
    private final PartyTransferGate transferGate;
    private final RedisEventPublisher redisPublisher;
    private final Logger logger;
    private final Map<UUID, PendingDeploy> pendingByPartyId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyIdByMember = new ConcurrentHashMap<>();

    public BreachPlayMatchmakingService(
            ProxyServer proxyServer,
            ServerRoutingService routingService,
            PartyService partyService,
            PartyTransferGate transferGate,
            RedisEventPublisher redisPublisher,
            Logger logger
    ) {
        this.proxyServer = proxyServer;
        this.routingService = routingService;
        this.partyService = partyService;
        this.transferGate = transferGate;
        this.redisPublisher = redisPublisher;
        this.logger = logger;
    }

    /**
     * Finds the best joinable breach in the network pool and routes the player's squad there.
     *
     * @return {@code true} when a cross-pod instance was reserved and transfers were started
     */
    public boolean matchmakeFromPlayCommand(Player leader, String mapId) {
        return matchmakeFromPlayCommand(leader, mapId, null, null);
    }

    /**
     * @param deployableMembers lobby members who still need a breach slot (omit members already raiding)
     * @param partyId           optional party id so routing can prefer the instance the squad already occupies
     */
    public boolean matchmakeFromPlayCommand(
            Player leader,
            String mapId,
            List<UUID> deployableMembers,
            UUID partyId
    ) {
        if (leader == null) {
            return false;
        }
        play(leader, SOUND_SEARCHING);
        UUID leaderId = leader.getUniqueId();
        UUID resolvedPartyId = partyId;
        List<UUID> memberIds = deployableMembers == null ? List.of() : List.copyOf(deployableMembers);
        if (memberIds.isEmpty()) {
            memberIds = List.of(leaderId);
        }
        if (this.partyService != null) {
            Optional<PartyService.PartyState> partyOpt = this.partyService.partyForMember(leaderId);
            if (partyOpt.isPresent()) {
                PartyService.PartyState party = partyOpt.get();
                if (!party.leaderId().equals(leaderId)) {
                    play(leader, SOUND_FAIL);
                    leader.sendMessage(ServerTextUtil.component("&eOnly the party leader can start a breach."));
                    return false;
                }
                if (resolvedPartyId == null) {
                    resolvedPartyId = party.partyId();
                }
                if (deployableMembers == null || deployableMembers.isEmpty()) {
                    memberIds = this.partyService.onlineMembers(this.proxyServer, party.partyId());
                }
            }
        }
        if (resolvedPartyId == null) {
            resolvedPartyId = leaderId;
        }
        if (memberIds.isEmpty()) {
            play(leader, SOUND_FAIL);
            return false;
        }
        if (!memberIds.contains(leaderId)) {
            memberIds = new ArrayList<>(memberIds);
            memberIds.add(0, leaderId);
        }

        UUID preferredPartyId = partyId;
        if (preferredPartyId == null && this.partyService != null) {
            preferredPartyId = this.partyService.partyForMember(leaderId)
                    .map(PartyService.PartyState::partyId)
                    .orElse(null);
        }

        Optional<ServerRoutingService.BreachInstanceTarget> targetOpt =
                this.routingService.selectBestBreachInstanceForParty(
                        memberIds.size(),
                        Set.of(),
                        mapId,
                        preferredPartyId
                );
        if (targetOpt.isEmpty()) {
            play(leader, SOUND_FAIL);
            return false;
        }

        ServerRoutingService.BreachInstanceTarget breachTarget = targetOpt.get();
        Optional<RegisteredServer> serverOpt = this.proxyServer.getServer(breachTarget.serverId());
        if (serverOpt.isEmpty()) {
            play(leader, SOUND_FAIL);
            return false;
        }

        if (this.redisPublisher == null) {
            play(leader, SOUND_FAIL);
            return false;
        }
        try {
            this.redisPublisher.publishJson(
                    NetworkChannels.BREACH_PARTY_QUEUE_DEPLOY,
                    new BreachPartyQueueDeployEvent(
                            resolvedPartyId,
                            leaderId,
                            breachTarget.serverId(),
                            breachTarget.instanceId(),
                            memberIds,
                            System.currentTimeMillis()
                    )
            );
        } catch (RuntimeException exception) {
            this.logger.warn(
                    "Failed to publish breach play deploy for {} -> {}#{}: {}",
                    leader.getUsername(),
                    breachTarget.serverId(),
                    breachTarget.instanceId(),
                    exception.getMessage()
            );
            play(leader, SOUND_FAIL);
            leader.sendMessage(ServerTextUtil.component("&cCould not reserve a breach slot right now. Try again."));
            return false;
        }

        this.registerPendingDeploy(
                resolvedPartyId,
                breachTarget.serverId(),
                breachTarget.instanceId(),
                memberIds
        );

        RegisteredServer targetServer = serverOpt.get();
        String targetServerId = targetServer.getServerInfo().getName();
        List<Player> online = new ArrayList<>();
        for (UUID memberId : memberIds) {
            this.proxyServer.getPlayer(memberId).ifPresent(online::add);
        }
        for (Player member : online) {
            if (this.transferGate != null) {
                this.transferGate.authorize(member.getUniqueId(), targetServerId);
            }
            play(member, SOUND_DEPLOY);
            member.createConnectionRequest(targetServer).fireAndForget();
            member.sendMessage(
                    ServerTextUtil.component(
                            "&aDeploying to breach &e" + breachTarget.mapId()
                                    + "&a on &f" + targetServerId + "&a."
                    )
            );
        }
        this.logger.info(
                "Breach play routed {} deployable member(s) to {}#{} on {}",
                online.size(),
                breachTarget.instanceId(),
                breachTarget.mapId(),
                targetServerId
        );
        return true;
    }

    /**
     * Marks {@code memberId} as arrived when they connect to their pending deploy's target pod. Arrived members no
     * longer cancel the squad deploy on disconnect, and once the whole squad has arrived the pending entry is dropped
     * (otherwise a post-deploy rage-quit inside the TTL would publish a spurious squad-wide deploy cancel).
     */
    public void completeDeployForMember(UUID memberId, String serverId) {
        if (memberId == null || serverId == null || serverId.isBlank()) {
            return;
        }
        UUID partyId = this.partyIdByMember.get(memberId);
        if (partyId == null) {
            return;
        }
        PendingDeploy pending = this.pendingByPartyId.get(partyId);
        if (pending == null) {
            this.partyIdByMember.remove(memberId, partyId);
            return;
        }
        if (!serverId.equalsIgnoreCase(pending.targetServerId())) {
            return;
        }
        this.partyIdByMember.remove(memberId, partyId);
        boolean anyInFlight = false;
        for (UUID pendingMemberId : pending.memberIds()) {
            if (partyId.equals(this.partyIdByMember.get(pendingMemberId))) {
                anyInFlight = true;
                break;
            }
        }
        if (!anyInFlight) {
            this.pendingByPartyId.remove(partyId, pending);
        }
    }

    /** Drops pending deploys past their TTL. Runs on the proxy memory sweep in addition to lazy call sites. */
    public void sweepExpiredPendingDeploys() {
        this.pruneExpiredPending();
    }

    /**
     * Cancels any in-flight breach deploy involving {@code memberId} (disconnect or party leave mid-join).
     * Aborts the entire squad deploy so remaining members are not left half-admitted.
     */
    public boolean cancelPendingDeployForMember(UUID memberId, String reason) {
        if (memberId == null) {
            return false;
        }
        this.pruneExpiredPending();
        UUID partyId = this.partyIdByMember.get(memberId);
        if (partyId == null) {
            return false;
        }
        return this.cancelPendingDeploy(partyId, reason == null ? "member_left" : reason);
    }

    public boolean cancelPendingDeploy(UUID partyId, String reason) {
        if (partyId == null) {
            return false;
        }
        PendingDeploy pending = this.pendingByPartyId.remove(partyId);
        if (pending == null) {
            return false;
        }
        for (UUID memberId : pending.memberIds()) {
            this.partyIdByMember.remove(memberId, partyId);
            if (this.transferGate != null) {
                this.transferGate.clear(memberId);
            }
        }
        if (this.redisPublisher != null) {
            try {
                this.redisPublisher.publishJson(
                        NetworkChannels.BREACH_PARTY_DEPLOY_CANCEL,
                        new BreachPartyDeployCancelEvent(
                                partyId,
                                pending.targetServerId(),
                                pending.instanceId(),
                                List.copyOf(pending.memberIds()),
                                reason == null ? "cancelled" : reason,
                                System.currentTimeMillis()
                        )
                );
            } catch (RuntimeException exception) {
                this.logger.warn("Failed to publish breach deploy cancel for party {}: {}", partyId, exception.getMessage());
            }
        }
        String message = "&eBreach deploy cancelled"
                + (reason == null || reason.isBlank() ? "." : " (" + humanReason(reason) + ").");
        for (UUID memberId : pending.memberIds()) {
            this.proxyServer.getPlayer(memberId).ifPresent(member -> {
                play(member, SOUND_CANCEL);
                member.sendMessage(ServerTextUtil.component(message));
            });
        }
        this.logger.info(
                "Cancelled breach deploy party={} instance={} reason={} members={}",
                partyId,
                pending.instanceId(),
                reason,
                pending.memberIds().size()
        );
        return true;
    }

    /** Tells the player's current extraction backend to provision a fresh local breach (no joinable pool entry). */
    public void requestLocalProvision(Player leader, String mapId) {
        if (leader == null) {
            return;
        }
        leader.getCurrentServer().ifPresent(connection -> {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("BREACH_PLAY_LOCAL");
            out.writeUTF(mapId == null ? "" : mapId.trim());
            connection.sendPluginMessage(ROUTE_CHANNEL, out.toByteArray());
        });
    }

    public void registerPendingDeploy(UUID partyId, String targetServerId, String instanceId, List<UUID> memberIds) {
        if (partyId == null || memberIds == null || memberIds.isEmpty()) {
            return;
        }
        PendingDeploy previous = this.pendingByPartyId.put(
                partyId,
                new PendingDeploy(
                        partyId,
                        targetServerId,
                        instanceId,
                        List.copyOf(memberIds),
                        System.currentTimeMillis() + PENDING_DEPLOY_TTL_MILLIS
                )
        );
        if (previous != null) {
            for (UUID memberId : previous.memberIds()) {
                this.partyIdByMember.remove(memberId, partyId);
            }
        }
        for (UUID memberId : memberIds) {
            if (memberId != null) {
                this.partyIdByMember.put(memberId, partyId);
            }
        }
    }

    private void pruneExpiredPending() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PendingDeploy> entry : this.pendingByPartyId.entrySet()) {
            PendingDeploy pending = entry.getValue();
            if (pending.expiresAtEpochMillis() > now) {
                continue;
            }
            if (this.pendingByPartyId.remove(entry.getKey(), pending)) {
                for (UUID memberId : pending.memberIds()) {
                    this.partyIdByMember.remove(memberId, entry.getKey());
                }
            }
        }
    }

    private static String humanReason(String reason) {
        return switch (reason) {
            case "disconnect" -> "a squad member disconnected";
            case "party_leave" -> "a squad member left the party";
            default -> reason.replace('_', ' ');
        };
    }

    private static void play(Player player, Sound sound) {
        if (player != null && sound != null) {
            player.playSound(sound);
        }
    }

    private record PendingDeploy(
            UUID partyId,
            String targetServerId,
            String instanceId,
            List<UUID> memberIds,
            long expiresAtEpochMillis
    ) {
    }
}
