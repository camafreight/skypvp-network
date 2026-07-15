package network.skypvp.extraction.integration;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.config.HitscanSettings;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Dev helper for previewing hitscan laser beams without firing a weapon. */
public final class HitscanLaserDebugService {

    private static volatile HitscanLaserDebugService instance;

    private final HitscanLaserBeamRenderer renderer;
    private final HitscanSettings settings;

    private HitscanLaserDebugService(HitscanLaserBeamRenderer renderer, HitscanSettings settings) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public static void register(HitscanLaserBeamRenderer renderer, HitscanSettings settings) {
        instance = new HitscanLaserDebugService(renderer, settings);
    }

    public static Optional<HitscanLaserDebugService> optional() {
        return Optional.ofNullable(instance);
    }

    public HitscanSettings settings() {
        return settings;
    }

    public void spawnPreview(
            Player player,
            Color color,
            double lengthBlocks,
            double thickness,
            long lifetimeTicks
    ) {
        Objects.requireNonNull(player, "player");
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location end = eye.clone().add(direction.clone().multiply(Math.max(1.0, lengthBlocks)));
        // start is eye; renderDirect applies eye-space muzzle tip estimate.
        renderer.renderDirect(
                player.getWorld(),
                eye,
                end,
                color != null ? color : settings.laserGlowColor(),
                thickness,
                lifetimeTicks,
                true
        );
    }
}
