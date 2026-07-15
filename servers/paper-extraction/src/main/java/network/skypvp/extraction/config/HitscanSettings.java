package network.skypvp.extraction.config;

import java.util.Locale;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.Particle;

public record HitscanSettings(
        boolean enabled,
        double maxRangeBlocks,
        double tracerSpacingBlocks,
        double tracerViewRangeBlocks,
        Particle tracerParticle,
        TracerMode tracerMode,
        boolean impactEffectsEnabled,
        double impactViewRangeBlocks,
        int impactBlockParticleCount,
        int impactEntityParticleCount,
        boolean impactBlockChipOverlay,
        float impactBlockChipDamage,
        Particle impactEntityParticle,
        long visualDeferTicks,
        int maxVisualJobsPerTick,
        int visualQueueCapacity,
        int maxTracerPoints,
        boolean asyncVisualPrep,
        /** Linger ticks after the bolt arrives at impact (cosmetic trail). */
        long laserLifetimeTicks,
        /** Travel speed for the visual bolt (blocks/second). Lifespan = distance / velocity. */
        double laserVelocityBlocksPerSecond,
        /** Fixed visual length of the traveling bolt (not the full ray). */
        double laserBoltLengthBlocks,
        double laserThickness,
        double laserViewRangeBlocks,
        double laserMaxLengthBlocks,
        /** Along look direction from eye toward tip (blocks). */
        double laserMuzzleForwardBlocks,
        /** Strafe right of look (blocks); positive = player's right. */
        double laserMuzzleRightBlocks,
        /** Up relative to look (blocks); negative drops toward the gun hand. */
        double laserMuzzleUpBlocks,
        String laserItemModel,
        boolean laserGlowing,
        Color laserGlowColor,
        Set<String> simulatedProjectileWeapons
) {
    public enum TracerMode {
        PARTICLES,
        LASER;

        public static TracerMode parse(String raw, TracerMode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return TracerMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    private static final Set<String> DEFAULT_SIMULATED = Set.of(
            "Combat_Knife",
            "Stim",
            "Grenade",
            "Semtex",
            "Flashbang",
            "Cluster_Grenade",
            "Airstrike",
            "Sky_Torch",
            "RPG_7",
            "Fatman"
    );

    public static HitscanSettings defaults() {
        return new HitscanSettings(
                true,
                120.0,
                3.0,
                48.0,
                Particle.CRIT,
                TracerMode.LASER,
                true,
                48.0,
                6,
                4,
                true,
                0.22f,
                Particle.ENCHANTED_HIT,
                1L,
                64,
                2048,
                20,
                true,
                4L,
                // ~32 b/s: mid-range shots stay readable in flight (damage remains instant).
                32.0,
                3.0,
                0.25,
                64.0,
                64.0,
                // Tip ≈ eye + look*fwd + right*r + up*u (Laser Carbine barrel tip ~1.2 item-blocks from origin).
                0.55,
                0.30,
                -0.18,
                "skypvp:laser_beam",
                false,
                Color.fromRGB(0x40F0FF),
                DEFAULT_SIMULATED
        );
    }

    /** Backward-compatible alias for {@link #laserMuzzleForwardBlocks()}. */
    public double laserMuzzleOffsetBlocks() {
        return laserMuzzleForwardBlocks;
    }

    public boolean usesHitscan(String weaponTitle) {
        if (!enabled || weaponTitle == null || weaponTitle.isBlank()) {
            return false;
        }
        return !simulatedProjectileWeapons.contains(normalizeWeaponTitle(weaponTitle));
    }

    public boolean usesLaserTracer() {
        return tracerMode == TracerMode.LASER;
    }

    public static String normalizeWeaponTitle(String weaponTitle) {
        return weaponTitle.trim();
    }

    public static Set<String> normalizeWeaponTitles(Iterable<String> titles) {
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            normalized.add(normalizeWeaponTitle(title));
        }
        return Set.copyOf(normalized);
    }

    public static Particle parseParticle(String raw, Particle fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static Color parseColor(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            if (value.length() == 6) {
                return Color.fromRGB(Integer.parseInt(value, 16));
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }
}
