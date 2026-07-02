package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import network.skypvp.paper.platform.Platforms;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Per-viewer packet mob used for layout NPCs.
 */
public final class PacketFakeMob {

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(200000);

    private final Plugin plugin;
    private final int entityId;
    private final UUID uuid;
    private final EntityType entityType;
    private Location location;
    private final float scale;
    private final boolean glowing;
    private final String glowColor;
    private final NpcFakeLightVisual lightVisual;
    private final Set<UUID> viewers = new HashSet<>();

    public PacketFakeMob(Plugin plugin, EntityType entityType, Location location, float scale, boolean glowing, String glowColor) {
        this.plugin = plugin;
        this.entityType = entityType == null ? EntityType.VILLAGER : entityType;
        this.location = location == null ? null : location.clone();
        this.scale = scale <= 0.0F ? 1.0F : scale;
        this.glowing = glowing;
        this.glowColor = glowColor;
        this.entityId = ENTITY_ID_COUNTER.incrementAndGet();
        this.uuid = UUID.randomUUID();
        this.lightVisual = this.location == null ? null : new NpcFakeLightVisual(plugin, this.location);
    }

    public void updateLocation(Location location) {
        if (location != null) {
            this.location = location.clone();
            if (this.lightVisual != null) {
                this.lightVisual.resetAnchor(this.location);
            }
        }
    }

    public void resync(Player viewer) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        this.viewers.remove(viewer.getUniqueId());
        this.showTo(viewer);
    }

    public void showTo(Player viewer) {
        if (viewer == null || !viewer.isOnline() || this.location == null || this.location.getWorld() == null) {
            return;
        }
        if (!this.viewers.add(viewer.getUniqueId())) {
            return;
        }
        this.sendSpawnPackets(viewer);
        Platforms.get(this.plugin).runOnPlayerLater(viewer, () -> {
            if (!viewer.isOnline() || !this.viewers.contains(viewer.getUniqueId())) {
                return;
            }
            PacketEventsBridge.send(
                viewer,
                new WrapperPlayServerEntityMetadata(this.entityId, this.buildMetadata()),
                this.plugin.getLogger(),
                "fake-mob-metadata-refresh"
            );
            if (this.glowing) {
                PacketGlowTeams.refreshPacketEntityTeam(
                        this.plugin,
                        viewer,
                        this.teamEntry(),
                        true,
                        this.glowColor,
                        false
                );
            } else {
                PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, this.teamEntry());
            }
        }, 2L);
        if (this.lightVisual != null) {
            this.lightVisual.showTo(viewer);
        }
    }

    private void sendSpawnPackets(Player viewer) {
        PacketEventsBridge.requireAvailable(this.plugin);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
            this.entityId,
            Optional.of(this.uuid),
            PacketEntityTypes.fromBukkit(this.entityType),
            new Vector3d(this.location.getX(), this.location.getY(), this.location.getZ()),
            this.location.getPitch(),
            this.location.getYaw(),
            this.location.getYaw(),
            0,
            Optional.empty()
        );
        PacketEventsBridge.send(viewer, spawn, this.plugin.getLogger(), "fake-mob-spawn");
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityMetadata(this.entityId, this.buildMetadata()),
            this.plugin.getLogger(),
            "fake-mob-metadata"
        );
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityHeadLook(this.entityId, this.location.getYaw()),
            this.plugin.getLogger(),
            "fake-mob-head"
        );
        this.applyGlowTeam(viewer);
    }

    private void applyGlowTeam(Player viewer) {
        if (!this.glowing) {
            PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, this.teamEntry());
            return;
        }
        PacketGlowTeams.applyPacketEntityTeam(
                this.plugin,
                viewer,
                this.teamEntry(),
                true,
                this.glowColor,
                false
        );
    }

    private String teamEntry() {
        return this.uuid.toString();
    }

    public boolean matchesGlow(boolean glowing, String glowColor) {
        if (this.glowing != glowing) {
            return false;
        }
        String left = this.glowColor == null ? "" : this.glowColor.trim().toLowerCase(java.util.Locale.ROOT);
        String right = glowColor == null ? "" : glowColor.trim().toLowerCase(java.util.Locale.ROOT);
        return left.equals(right);
    }

    public void destroy(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !this.viewers.remove(viewer.getUniqueId())) {
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerDestroyEntities(this.entityId),
            this.plugin.getLogger(),
            "fake-mob-destroy"
        );
        PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, this.teamEntry());
        if (this.lightVisual != null) {
            this.lightVisual.hideFrom(viewer);
        }
    }

    public void updateRotation(Player viewer, Location targetLocation) {
        if (viewer == null || targetLocation == null || !this.viewers.contains(viewer.getUniqueId())) {
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityRotation(
                this.entityId,
                targetLocation.getYaw(),
                targetLocation.getPitch(),
                true
            ),
            this.plugin.getLogger(),
            "fake-mob-rotation"
        );
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityHeadLook(this.entityId, targetLocation.getYaw()),
            this.plugin.getLogger(),
            "fake-mob-head"
        );
    }

    private List<EntityData<?>> buildMetadata() {
        List<EntityData<?>> metadata = new ArrayList<>();
        byte flagBits = 0;
        if (this.glowing) {
            flagBits |= 0x40;
        }
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, flagBits));
        return metadata;
    }
}
