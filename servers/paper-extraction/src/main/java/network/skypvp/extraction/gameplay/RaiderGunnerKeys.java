package network.skypvp.extraction.gameplay;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/** Persistent tags so Ruins gunners can be re-identified after chunk unload or Mythic desync. */
public final class RaiderGunnerKeys {

    private static NamespacedKey mobTypeKey;
    private static NamespacedKey levelKey;

    private RaiderGunnerKeys() {
    }

    public static void register(JavaPlugin plugin) {
        mobTypeKey = new NamespacedKey(plugin, "ruins_gunner_type");
        levelKey = new NamespacedKey(plugin, "ruins_gunner_level");
    }

    public static NamespacedKey mobTypeKey() {
        return mobTypeKey;
    }

    public static NamespacedKey levelKey() {
        return levelKey;
    }
}
