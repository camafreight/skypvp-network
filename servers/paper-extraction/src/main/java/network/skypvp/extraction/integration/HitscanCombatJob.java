package network.skypvp.extraction.integration;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/** In-flight WM projectile plus ray parameters; processed on the owning region thread. */
final class HitscanCombatJob {

    private final Object projectile;
    private final Location start;
    private final Vector direction;
    private final double range;

    HitscanCombatJob(Object projectile, Location start, Vector direction, double range) {
        this.projectile = projectile;
        this.start = start.clone();
        this.direction = direction.clone();
        this.range = range;
    }

    Object projectile() {
        return projectile;
    }

    Location start() {
        return start.clone();
    }

    Vector direction() {
        return direction.clone();
    }

    double range() {
        return range;
    }
}
