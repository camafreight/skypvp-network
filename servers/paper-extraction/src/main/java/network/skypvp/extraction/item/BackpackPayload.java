package network.skypvp.extraction.item;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Binary payload of a raid backpack. Everything travels inside the item itself, so the
 * backpack survives server transfers, deaths, and trades without a database row:
 *
 * <ul>
 *   <li>{@code tier} — 1..4, each tier unlocks one inventory row (hotbar last)</li>
 *   <li>{@code viewOpen} — true while the owner's main inventory is swapped to backpack view;
 *       used for crash recovery on the next join</li>
 *   <li>{@code skin} — selected cosmetic skin id ({@link BackpackSkins}); unlocks are
 *       permission-driven, only the choice travels with the item</li>
 *   <li>{@code contents} — stored items (serialized into the worn backpack item on close)</li>
 *   <li>{@code stash} — legacy canvas-era snapshot of the main grid; restored once on close if present</li>
 * </ul>
 */
public record BackpackPayload(int tier, boolean viewOpen, String skin, List<ItemStack> contents, List<ItemStack> stash) {

    private static final int VERSION = 2;

    public BackpackPayload {
        tier = Math.max(1, Math.min(BackpackDefinition.MAX_TIER, tier));
        skin = BackpackSkins.byId(skin).id();
        // NOT List.copyOf: these lists are positional (slot index) and deliberately carry
        // null entries for empty slots — List.copyOf rejects nulls and broke every open.
        contents = contents == null
                ? List.of()
                : java.util.Collections.unmodifiableList(new ArrayList<>(contents));
        stash = stash == null
                ? List.of()
                : java.util.Collections.unmodifiableList(new ArrayList<>(stash));
    }

    public static BackpackPayload empty(int tier) {
        return new BackpackPayload(tier, false, BackpackSkins.DEFAULT_ID, List.of(), List.of());
    }

    public int unlockedRows() {
        return tier;
    }

    public int capacity() {
        return tier * 9;
    }

    public BackpackPayload withContents(List<ItemStack> newContents) {
        return new BackpackPayload(tier, viewOpen, skin, newContents, stash);
    }

    public BackpackPayload withView(boolean open, List<ItemStack> newStash) {
        return new BackpackPayload(tier, open, skin, contents, newStash);
    }

    public BackpackPayload withSkin(String newSkin) {
        return new BackpackPayload(tier, viewOpen, newSkin, contents, stash);
    }

    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(tier);
            out.writeBoolean(viewOpen);
            out.writeUTF(skin);
            writeItems(out, contents);
            writeItems(out, stash);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode backpack payload", ex);
        }
        return bytes.toByteArray();
    }

    public static BackpackPayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return empty(1);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version != 1 && version != VERSION) {
                return empty(1);
            }
            int tier = in.readUnsignedByte();
            boolean open = in.readBoolean();
            // Version 1 predates skins — packs minted before the skin update keep the default.
            String skin = version >= 2 ? in.readUTF() : BackpackSkins.DEFAULT_ID;
            List<ItemStack> contents = readItems(in);
            List<ItemStack> stash = readItems(in);
            return new BackpackPayload(tier, open, skin, contents, stash);
        } catch (IOException | RuntimeException ex) {
            return empty(1);
        }
    }

    private static void writeItems(DataOutputStream out, List<ItemStack> items) throws IOException {
        out.writeInt(items.size());
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                out.writeInt(0);
                continue;
            }
            byte[] encoded = item.serializeAsBytes();
            out.writeInt(encoded.length);
            out.write(encoded);
        }
    }

    private static List<ItemStack> readItems(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 256) {
            throw new IOException("Corrupt item list size " + size);
        }
        List<ItemStack> items = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            int length = in.readInt();
            if (length <= 0) {
                items.add(null);
                continue;
            }
            byte[] encoded = in.readNBytes(length);
            try {
                items.add(ItemStack.deserializeBytes(encoded));
            } catch (RuntimeException ignored) {
                // Item from a newer/older data version that no longer decodes — drop the slot,
                // never the whole backpack.
                items.add(null);
            }
        }
        return items;
    }
}
