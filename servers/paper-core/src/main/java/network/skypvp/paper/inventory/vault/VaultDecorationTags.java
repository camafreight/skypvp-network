package network.skypvp.paper.inventory.vault;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Internal PDC tags for vault decorative slots (locked / purchasable barriers). */
public final class VaultDecorationTags {

    public static final String KIND_LOCKED = "locked";
    public static final String KIND_PURCHASABLE = "purchasable";
    public static final String KIND_UNAVAILABLE = "unavailable";

    private static NamespacedKey kindKey;

    private VaultDecorationTags() {
    }

    public static void init(JavaPlugin plugin) {
        kindKey = new NamespacedKey(plugin, "vault_slot_kind");
    }

    public static ItemStack tag(ItemStack item, String kind) {
        if (kindKey == null || item == null || kind == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean hasKind(ItemStack item, String kind) {
        if (kindKey == null || item == null || kind == null || !item.hasItemMeta()) {
            return false;
        }
        String stored = item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return kind.equals(stored);
    }

    public static boolean isDecorative(ItemStack item) {
        if (kindKey == null || item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(kindKey, PersistentDataType.STRING);
    }
}
