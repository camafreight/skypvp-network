package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Bed.Part;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Client-side fake bed blocks sent via PacketEvents block change packets.
 */
public final class PacketFakeBedVisual {

    private final Plugin plugin;
    private final Location footBlock;
    private final Location headBlock;
    private final Set<UUID> viewers = new HashSet<>();

    public PacketFakeBedVisual(Plugin plugin, Location anchor, float yaw) {
        this.plugin = plugin;
        BlockFace facing = yawToFace(yaw);
        Location blockAnchor = anchor.getBlock().getLocation();
        this.footBlock = blockAnchor.clone();
        this.headBlock = blockAnchor.clone().add(facing.getModX(), 0, facing.getModZ());
    }

    public Location footBlock() {
        return this.footBlock.clone();
    }

    public Location headBlock() {
        return this.headBlock.clone().add(0.5, 0.0, 0.5);
    }

    public void showTo(Player player) {
        if (player == null || !this.viewers.add(player.getUniqueId())) {
            return;
        }
        PacketEventsBridge.requireAvailable(this.plugin);
        this.sendBedBlock(player, this.footBlock, Part.FOOT);
        this.sendBedBlock(player, this.headBlock, Part.HEAD);
    }

    public void hideFrom(Player player) {
        if (player == null || !this.viewers.remove(player.getUniqueId())) {
            return;
        }
        this.sendAirBlock(player, this.footBlock);
        this.sendAirBlock(player, this.headBlock);
    }

    public void resync(Player player) {
        this.hideFrom(player);
        this.viewers.remove(player.getUniqueId());
        this.showTo(player);
    }

    public Set<UUID> viewersSnapshot() {
        return Set.copyOf(this.viewers);
    }

    private void sendBedBlock(Player player, Location blockLocation, Part part) {
        Bed bedData = (Bed) org.bukkit.Bukkit.createBlockData(Material.RED_BED);
        bedData.setPart(part);
        bedData.setFacing(facingBetween(this.footBlock, this.headBlock));
        WrappedBlockState state = WrappedBlockState.getByString(bedData.getAsString());
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
            new Vector3i(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ()),
            state.getGlobalId()
        );
        PacketEventsBridge.send(player, packet, this.plugin.getLogger(), "fake-bed-show");
    }

    private void sendAirBlock(Player player, Location blockLocation) {
        WrappedBlockState air = WrappedBlockState.getDefaultState(StateTypes.AIR);
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(
            new Vector3i(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ()),
            air.getGlobalId()
        );
        PacketEventsBridge.send(player, packet, this.plugin.getLogger(), "fake-bed-hide");
    }

    private static BlockFace facingBetween(Location foot, Location head) {
        int dx = head.getBlockX() - foot.getBlockX();
        int dz = head.getBlockZ() - foot.getBlockZ();
        if (dx > 0) {
            return BlockFace.EAST;
        }
        if (dx < 0) {
            return BlockFace.WEST;
        }
        if (dz > 0) {
            return BlockFace.SOUTH;
        }
        if (dz < 0) {
            return BlockFace.NORTH;
        }
        return BlockFace.SOUTH;
    }

    private static BlockFace yawToFace(float yaw) {
        float normalized = (yaw % 360.0F + 360.0F) % 360.0F;
        if (normalized >= 45.0F && normalized < 135.0F) {
            return BlockFace.WEST;
        }
        if (normalized >= 135.0F && normalized < 225.0F) {
            return BlockFace.NORTH;
        }
        if (normalized >= 225.0F && normalized < 315.0F) {
            return BlockFace.EAST;
        }
        return BlockFace.SOUTH;
    }
}
