package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import network.skypvp.paper.platform.Platforms;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Per-viewer fake player NPC using PacketEvents instead of raw NMS reflection.
 */
public final class PacketFakePlayer {

    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(100000);
    /** Displayed-skin-parts data index on the Avatar class (MC 1.21.9+). Was 17 on pre-Avatar Player. */
    private static final int SKIN_PARTS_METADATA_INDEX = 16;

    private final Plugin plugin;
    private final int entityId;
    private final UUID uuid;
    private final UserProfile profile;
    private Location location;
    private final String profileName;
    private final boolean glowing;
    private final String glowColor;
    /**
     * When true, the fake player renders prone (lying flat) at its own location using the SWIMMING pose, instead of
     * standing. Used for death corpses so the body lies exactly where the player died — unlike the SLEEPING pose,
     * which snaps the body onto a bed block and therefore cannot represent an arbitrary death position.
     */
    private final boolean lyingCorpse;
    private final NpcFakeLightVisual lightVisual;
    private final Set<UUID> viewers = new HashSet<>();

    public PacketFakePlayer(Plugin plugin, String name, String textureValue, String textureSignature, Location location, boolean glowing) {
        this(plugin, name, textureValue, textureSignature, location, glowing, null, false);
    }

    public PacketFakePlayer(
        Plugin plugin,
        String name,
        String textureValue,
        String textureSignature,
        Location location,
        boolean glowing,
        String glowColor
    ) {
        this(plugin, name, textureValue, textureSignature, location, glowing, glowColor, false);
    }

    public PacketFakePlayer(
        Plugin plugin,
        String name,
        String textureValue,
        String textureSignature,
        Location location,
        boolean glowing,
        boolean lyingCorpse
    ) {
        this(plugin, name, textureValue, textureSignature, location, glowing, null, lyingCorpse);
    }

    public PacketFakePlayer(
        Plugin plugin,
        String name,
        String textureValue,
        String textureSignature,
        Location location,
        boolean glowing,
        String glowColor,
        boolean lyingCorpse
    ) {
        this.plugin = plugin;
        this.glowing = glowing;
        this.glowColor = glowColor;
        this.lyingCorpse = lyingCorpse;
        this.entityId = ENTITY_ID_COUNTER.incrementAndGet();
        this.uuid = UUID.randomUUID();
        this.profileName = sanitizeName(name);
        this.profile = buildProfile(this.uuid, this.profileName, textureValue, textureSignature);
        this.location = location == null ? null : location.clone();
        this.lightVisual = lyingCorpse || this.location == null
                ? null
                : new NpcFakeLightVisual(plugin, this.location);
    }

    public String getProfileName() {
        return this.profileName;
    }

    public int getEntityId() {
        return this.entityId;
    }

    public Set<UUID> getViewersSnapshot() {
        return Set.copyOf(this.viewers);
    }

    public void resync(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        this.destroy(player);
        this.showTo(player);
    }

    public void resyncCorpseMetadata(Player viewer) {
        if (viewer == null || !this.lyingCorpse || !this.viewers.contains(viewer.getUniqueId())) {
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityMetadata(this.entityId, this.buildMetadata()),
            this.plugin.getLogger(),
            "fake-player-corpse-metadata"
        );
    }

    public void refreshGlow(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !this.viewers.contains(viewer.getUniqueId())) {
            return;
        }
        if (this.glowing) {
            PacketEventsBridge.send(
                viewer,
                new WrapperPlayServerEntityMetadata(this.entityId, this.buildMetadata()),
                this.plugin.getLogger(),
                "fake-player-glow-metadata"
            );
        }
        PacketGlowTeams.refreshPacketEntityTeam(
                this.plugin,
                viewer,
                this.profileName,
                this.glowing,
                this.glowColor,
                true
        );
    }

    public void showTo(Player player) {
        if (player == null || !player.isOnline() || this.location == null || !this.viewers.add(player.getUniqueId())) {
            return;
        }
        PacketEventsBridge.requireAvailable(this.plugin);

        // Register the profile (so the skin/texture resolves) but mark it unlisted from the start. Adding it as
        // listed=true and flipping it to false a few ticks later is what made the name flicker in/out of the TAB
        // list on every (re)spawn, and corpses/NPCs re-sync frequently. listed=false up front never shows it.
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            this.profile,
            false,
            0,
            GameMode.SURVIVAL,
            null,
            null
        );
        WrapperPlayServerPlayerInfoUpdate tabList = new WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE
            ),
            info
        );
        PacketEventsBridge.send(player, tabList, this.plugin.getLogger(), "fake-player-tab");

        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
            this.entityId,
            Optional.of(this.uuid),
            EntityTypes.PLAYER,
            this.vector(this.location),
            this.location.getPitch(),
            this.location.getYaw(),
            this.location.getYaw(),
            0,
            Optional.empty()
        );
        PacketEventsBridge.send(player, spawn, this.plugin.getLogger(), "fake-player-spawn");

        PacketEventsBridge.send(
            player,
            new WrapperPlayServerEntityMetadata(this.entityId, this.buildMetadata()),
            this.plugin.getLogger(),
            "fake-player-metadata"
        );
        PacketEventsBridge.send(
            player,
            new WrapperPlayServerEntityHeadLook(this.entityId, this.location.getYaw()),
            this.plugin.getLogger(),
            "fake-player-head"
        );

        this.sendViewerTeam(player);

        ServerPlatform scheduler = Platforms.get(this.plugin);
        scheduler.runOnPlayerLater(player, () -> {
            if (!player.isOnline() || !this.viewers.contains(player.getUniqueId())) {
                return;
            }
            // Re-assert glow metadata + team once the profile/spawn is fully registered client-side.
            this.refreshGlow(player);
        }, 2L);
        scheduler.runOnPlayerLater(player, () -> {
            if (!player.isOnline() || !this.viewers.contains(player.getUniqueId())) {
                return;
            }
            this.refreshGlow(player);
        }, 5L);

        if (this.lightVisual != null) {
            this.lightVisual.showTo(player);
        }
    }

    public void updateRotation(Player viewer, Location targetLocation) {
        if (viewer == null || !viewer.isOnline() || targetLocation == null || !this.viewers.contains(viewer.getUniqueId())) {
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
            "fake-player-rotation"
        );
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityHeadLook(this.entityId, targetLocation.getYaw()),
            this.plugin.getLogger(),
            "fake-player-head-rotation"
        );
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerEntityTeleport(
                this.entityId,
                this.vector(this.location),
                targetLocation.getYaw(),
                targetLocation.getPitch(),
                false
            ),
            this.plugin.getLogger(),
            "fake-player-teleport-rotation"
        );
    }

    public void destroy(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !this.viewers.remove(viewer.getUniqueId())) {
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerDestroyEntities(this.entityId),
            this.plugin.getLogger(),
            "fake-player-destroy"
        );
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerPlayerInfoRemove(this.uuid),
            this.plugin.getLogger(),
            "fake-player-tab-remove"
        );
        this.removeViewerTeam(viewer);
        if (this.lightVisual != null) {
            this.lightVisual.hideFrom(viewer);
        }
    }

    public boolean matchesGlow(boolean glowing, String glowColor) {
        if (this.glowing != glowing) {
            return false;
        }
        String left = this.glowColor == null ? "" : this.glowColor.trim().toLowerCase(java.util.Locale.ROOT);
        String right = glowColor == null ? "" : glowColor.trim().toLowerCase(java.util.Locale.ROOT);
        return left.equals(right);
    }

    private void sendViewerTeam(Player viewer) {
        PacketGlowTeams.applyPacketEntityTeam(
                this.plugin,
                viewer,
                this.profileName,
                this.glowing,
                this.glowColor,
                true
        );
    }

    private void removeViewerTeam(Player viewer) {
        PacketGlowTeams.removePacketEntityTeam(this.plugin, viewer, this.profileName);
    }

    private List<EntityData<?>> buildMetadata() {
        List<EntityData<?>> metadata = new ArrayList<>();
        int flagBits = 0;
        if (this.glowing) {
            flagBits |= 0x40;
        }
        if (this.lyingCorpse) {
            // 0x10 = "is swimming". The client only renders the prone SWIMMING pose when this status bit is set
            // alongside the pose; without it the body stays upright.
            flagBits |= 0x10;
        }
        metadata.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) flagBits));
        // MC 1.21.9+ ("Avatar" metadata refactor): displayed skin parts is index 16 (Byte).
        // Index 17 became "Additional Hearts" (Float) on Player, so sending a Byte there
        // triggers a client-side type mismatch -> "Network Protocol Error" kick.
        metadata.add(new EntityData<>(SKIN_PARTS_METADATA_INDEX, EntityDataTypes.BYTE, (byte) 127));
        if (this.lyingCorpse) {
            // SWIMMING lays the player flat at the entity's own position (the exact death spot), unlike SLEEPING
            // which would snap the body onto a bed block and ignore the real location.
            metadata.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, EntityPose.SWIMMING));
        }
        return metadata;
    }

    private Vector3d vector(Location location) {
        return new Vector3d(location.getX(), location.getY(), location.getZ());
    }

    private static String sanitizeName(String name) {
        String safeName = name == null ? "NPC" : name.replaceAll("[^a-zA-Z0-9_]", "");
        if (safeName.length() > 16) {
            safeName = safeName.substring(0, 16);
        }
        if (safeName.isEmpty()) {
            safeName = "NPC";
        }
        return safeName;
    }

    private static UserProfile buildProfile(UUID uuid, String name, String textureValue, String textureSignature) {
        UserProfile profile = new UserProfile(uuid, name, List.of());
        if (textureValue != null && !textureValue.isEmpty()) {
            profile.setTextureProperties(List.of(new TextureProperty(
                "textures",
                textureValue,
                textureSignature == null ? "" : textureSignature
            )));
        }
        return profile;
    }
}
