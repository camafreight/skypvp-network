package network.skypvp.paper.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

final class CustomItemKeys {

    final NamespacedKey itemType;
    final NamespacedKey itemInstance;
    final NamespacedKey itemVersion;
    final NamespacedKey itemPayload;

    CustomItemKeys(Plugin plugin) {
        this.itemType = new NamespacedKey(plugin, "item_type");
        this.itemInstance = new NamespacedKey(plugin, "item_instance");
        this.itemVersion = new NamespacedKey(plugin, "item_version");
        this.itemPayload = new NamespacedKey(plugin, "item_payload");
    }
}
