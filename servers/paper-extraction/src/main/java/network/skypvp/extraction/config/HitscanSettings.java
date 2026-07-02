package network.skypvp.extraction.config;

import java.util.Locale;
import java.util.Set;
import org.bukkit.Particle;

public record HitscanSettings(
        boolean enabled,
        double maxRangeBlocks,
        double tracerSpacingBlocks,
        double tracerViewRangeBlocks,
        Particle tracerParticle,
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
        int combatDispatchThreads,
        int combatQueueCapacity,
        long combatDeferTicks,
        Set<String> simulatedProjectileWeapons
) {
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
                4,
                4096,
                0L,
                DEFAULT_SIMULATED
        );
    }

    public boolean usesHitscan(String weaponTitle) {
        if (!enabled || weaponTitle == null || weaponTitle.isBlank()) {
            return false;
        }
        return !simulatedProjectileWeapons.contains(normalizeWeaponTitle(weaponTitle));
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
}
