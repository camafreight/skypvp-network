package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Per-viewer block-light patches around decoration anchors. Uses {@link WrapperPlayServerUpdateLight}
 * so clients receive light without fake block state changes.
 */
public final class NpcFakeLightVisual {

    private static final int LIGHT_LEVEL = 15;

    private final Plugin plugin;
    private final List<Location> lightBlocks = new ArrayList<>();
    private final Set<UUID> viewers = new HashSet<>();

    public NpcFakeLightVisual(Plugin plugin, Location anchor) {
        this.plugin = plugin;
        this.resetAnchor(anchor);
    }

    public void resetAnchor(Location anchor) {
        this.lightBlocks.clear();
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        Location base = anchor.getBlock().getLocation();
        this.addLightBlock(base);
        this.addLightBlock(base.clone().add(0.0, 1.0, 0.0));
        this.addLightBlock(base.clone().add(0.0, 2.0, 0.0));
    }

    public boolean matchesAnchor(Location anchor) {
        if (anchor == null || this.lightBlocks.isEmpty()) {
            return false;
        }
        Location expected = anchor.getBlock().getLocation();
        Location current = this.lightBlocks.get(0);
        return current.getWorld().equals(expected.getWorld())
                && current.getBlockX() == expected.getBlockX()
                && current.getBlockY() == expected.getBlockY()
                && current.getBlockZ() == expected.getBlockZ();
    }

    public void showTo(Player player) {
        if (player == null || !player.isOnline() || this.lightBlocks.isEmpty() || !this.viewers.add(player.getUniqueId())) {
            return;
        }
        this.sendLightPatch(player, true);
    }

    public void hideFrom(Player player) {
        if (player == null || !player.isOnline() || this.lightBlocks.isEmpty() || !this.viewers.remove(player.getUniqueId())) {
            return;
        }
        this.sendLightPatch(player, false);
    }

    public void resync(Player player) {
        if (player == null) {
            return;
        }
        this.hideFrom(player);
        this.viewers.remove(player.getUniqueId());
        this.showTo(player);
    }

    private void sendLightPatch(Player player, boolean boosted) {
        if (!PacketEventsBridge.isAvailable()) {
            return;
        }
        World world = this.lightBlocks.get(0).getWorld();
        if (world == null || !player.getWorld().equals(world)) {
            return;
        }

        Map<Long, SectionPatch> patches = this.groupBySection(world);
        for (SectionPatch patch : patches.values()) {
            Chunk chunk = world.getChunkAt(patch.chunkX, patch.chunkZ);
            byte[] sectionLight = this.buildSectionBlockLight(chunk, patch.sectionIndex, patch.localBlocks, boosted);
            LightData lightData = this.buildLightData(world, patch.sectionIndex, sectionLight);
            PacketEventsBridge.send(
                    player,
                    new WrapperPlayServerUpdateLight(patch.chunkX, patch.chunkZ, lightData),
                    this.plugin.getLogger(),
                    boosted ? "npc-light-show" : "npc-light-hide"
            );
        }
    }

    private Map<Long, SectionPatch> groupBySection(World world) {
        Map<Long, SectionPatch> patches = new HashMap<>();
        int minHeight = world.getMinHeight();
        for (Location block : this.lightBlocks) {
            if (block.getWorld() == null || !block.getWorld().equals(world)) {
                continue;
            }
            int chunkX = block.getBlockX() >> 4;
            int chunkZ = block.getBlockZ() >> 4;
            int sectionIndex = (block.getBlockY() - minHeight) >> 4;
            long key = sectionKey(chunkX, chunkZ, sectionIndex);
            SectionPatch patch = patches.computeIfAbsent(key, ignored -> new SectionPatch(chunkX, chunkZ, sectionIndex));
            patch.localBlocks.add(new int[] {
                    block.getBlockX() & 15,
                    (block.getBlockY() - minHeight) & 15,
                    block.getBlockZ() & 15
            });
        }
        return patches;
    }

    private byte[] buildSectionBlockLight(Chunk chunk, int sectionIndex, List<int[]> localBlocks, boolean boosted) {
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false, true);
        World world = chunk.getWorld();
        int sectionBaseY = world.getMinHeight() + sectionIndex * 16;
        byte[] data = new byte[2048];

        for (int localX = 0; localX < 16; localX++) {
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int emitted = snapshot.getBlockEmittedLight(localX, sectionBaseY + localY, localZ);
                    setNibble(data, localX, localY, localZ, emitted);
                }
            }
        }

        if (boosted) {
            for (int[] local : localBlocks) {
                int current = getNibble(data, local[0], local[1], local[2]);
                setNibble(data, local[0], local[1], local[2], Math.max(current, LIGHT_LEVEL));
            }
        }

        return data;
    }

    private LightData buildLightData(World world, int sectionIndex, byte[] sectionLight) {
        int sectionCount = ((world.getMaxHeight() - world.getMinHeight()) + 15) >> 4;
        BitSet blockLightMask = new BitSet(sectionCount);
        blockLightMask.set(sectionIndex);
        byte[][] blockLightArray = new byte[sectionCount][];
        blockLightArray[sectionIndex] = sectionLight;
        return new LightData(
                false,
                blockLightMask,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                sectionCount,
                sectionCount,
                new byte[sectionCount][],
                blockLightArray
        );
    }

    private void addLightBlock(Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return;
        }
        this.lightBlocks.add(blockLocation.getBlock().getLocation());
    }

    private static long sectionKey(int chunkX, int chunkZ, int sectionIndex) {
        return ((long) chunkX & 0xFFFFFFFFL) << 32 | ((long) chunkZ & 0xFFFFL) << 16 | (sectionIndex & 0xFFFFL);
    }

    private static int getNibble(byte[] array, int x, int y, int z) {
        int index = (y << 8) | (z << 4) | x;
        int byteIndex = index >> 1;
        if ((index & 1) == 0) {
            return array[byteIndex] & 0x0F;
        }
        return (array[byteIndex] >> 4) & 0x0F;
    }

    private static void setNibble(byte[] array, int x, int y, int z, int value) {
        int index = (y << 8) | (z << 4) | x;
        int byteIndex = index >> 1;
        int clamped = Math.max(0, Math.min(15, value));
        if ((index & 1) == 0) {
            array[byteIndex] = (byte) ((array[byteIndex] & 0xF0) | clamped);
        } else {
            array[byteIndex] = (byte) ((array[byteIndex] & 0x0F) | (clamped << 4));
        }
    }

    private static final class SectionPatch {
        private final int chunkX;
        private final int chunkZ;
        private final int sectionIndex;
        private final List<int[]> localBlocks = new ArrayList<>();

        private SectionPatch(int chunkX, int chunkZ, int sectionIndex) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionIndex = sectionIndex;
        }
    }
}
