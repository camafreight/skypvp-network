package network.skypvp.paper.nms;

import network.skypvp.paper.nms.NoopHeadlessPlayerService;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads the NMS {@link HeadlessPlayerService} implementation when the shaded paper-nms classes are present. */
public final class HeadlessPlayerServices {

    private static final String IMPLEMENTATION = "network.skypvp.paper.nms.impl.NmsHeadlessPlayerService";

    private HeadlessPlayerServices() {
    }

    public static HeadlessPlayerService load(JavaPlugin plugin) {
        try {
            Class<?> implementation = Class.forName(IMPLEMENTATION);
            Object service = implementation.getConstructor(org.bukkit.plugin.Plugin.class).newInstance(plugin);
            if (service instanceof HeadlessPlayerService headless) {
                if (headless.isAvailable()) {
                    plugin.getLogger().info("[HeadlessPlayer] NMS backend ready (Variant 3 hang + Variant 2 spawn fallback).");
                } else {
                    plugin.getLogger().warning("[HeadlessPlayer] NMS classes loaded but backend unavailable; using no-op.");
                }
                return headless;
            }
        } catch (Throwable failure) {
            plugin.getLogger().warning("[HeadlessPlayer] NMS backend unavailable: " + failure.getMessage());
        }
        return new NoopHeadlessPlayerService();
    }
}
