package network.skypvp.extraction.ai.raider;

import java.util.Locale;

/** Weapon/combat tuning per Ruins gunner archetype, optionally scaled by Mythic level. */
public record RaiderWeaponProfile(
        String primaryWeapon,
        String sidearmWeapon,
        String meleeWeapon,
        int magazineSize,
        int spareMagazines,
        int sidearmMagazineSize,
        int burstShots,
        int ticksBetweenBurstShots,
        int ticksBetweenBursts,
        double primarySpread,
        double sidearmSpread,
        double engageRangeSq,
        double minEngageRangeSq,
        double meleeRangeSq,
        double meleeDamage,
        int meleeCooldownTicks,
        int healCharges,
        double healAmount,
        long healTicks,
        long coverBreakTicks,
        double hurtHealRatio,
        double moveSpeed,
        RaiderCombatStyle style,
        double preferredCloseRangeSq,
        double acquisitionFovDegrees
) {
    public static RaiderWeaponProfile forMobType(String mobType) {
        return forMobType(mobType, 1);
    }

    public static RaiderWeaponProfile forMobType(String mobType, int level) {
        RaiderWeaponProfile base;
        if (mobType == null) {
            base = rifleman();
        } else {
            base = switch (mobType.toLowerCase(Locale.ROOT)) {
                case "ruinssmgunner" -> smg();
                case "ruinsbreacher" -> breacher();
                case "ruinspistolgunner" -> pistol();
                case "ruinskniferusher" -> knifeRusher();
                case "ruinsrifleman" -> rifleman();
                default -> rifleman();
            };
        }
        return base.scaledForLevel(Math.max(1, level));
    }

    /**
     * Higher levels tighten spread slightly and speed bursts a bit — never to terminator accuracy.
     * Level 1 is the safe baseline; L3 still misses often.
     */
    public RaiderWeaponProfile scaledForLevel(int level) {
        int clamped = Math.max(1, Math.min(level, 5));
        if (clamped <= 1 || isKnifeRusher()) {
            return this;
        }
        double tighten = 1.0D - (clamped - 1) * 0.10D; // L3 ≈ 0.80× spread
        int burstGap = Math.max(2, (int) Math.round(ticksBetweenBurstShots * (1.0D - (clamped - 1) * 0.06D)));
        int burstPause = Math.max(14, (int) Math.round(ticksBetweenBursts * (1.0D - (clamped - 1) * 0.05D)));
        return new RaiderWeaponProfile(
                primaryWeapon, sidearmWeapon, meleeWeapon,
                magazineSize, spareMagazines, sidearmMagazineSize,
                burstShots, burstGap, burstPause,
                primarySpread * tighten, sidearmSpread * tighten,
                engageRangeSq, minEngageRangeSq, meleeRangeSq,
                meleeDamage, meleeCooldownTicks,
                healCharges, healAmount, healTicks, coverBreakTicks, hurtHealRatio,
                moveSpeed, style, preferredCloseRangeSq, acquisitionFovDegrees
        );
    }

    public boolean prefersCloseCombat() {
        return style == RaiderCombatStyle.CLOSE_ASSAULT
                || style == RaiderCombatStyle.BREACHER
                || style == RaiderCombatStyle.KNIFE_RUSHER;
    }

    public boolean isBreacher() {
        return style == RaiderCombatStyle.BREACHER;
    }

    public boolean isRifleDoctrine() {
        return style == RaiderCombatStyle.RIFLE;
    }

    public boolean isKnifeRusher() {
        return style == RaiderCombatStyle.KNIFE_RUSHER;
    }

    /** Solo gunners with this style push to knife once inside preferred close range. */
    public boolean shouldPushToMeleeWhenSolo(double distanceSq) {
        return prefersCloseCombat() && distanceSq <= preferredCloseRangeSq;
    }

    /** Rifle: controlled 5-round bursts, tight cone, climb pattern, pause to reacquire. */
    public static RaiderWeaponProfile rifleman() {
        return new RaiderWeaponProfile(
                "AK_47", "357_Magnum", "Combat_Knife",
                30, 2, 6,
                5, 3, 28,
                5.0D, 7.5D,
                34.0D * 34.0D, 2.5D * 2.5D, 3.25D * 3.25D,
                6.0D, 10,
                1, 15.0D, 40L, 24L, 0.55D,
                // Base attribute = walking-player parity (0.24 ≈ 4.3 m/s). Chase pace comes from the
                // navigation multipliers, capped at sprinting-player pace by MobNavigationSupport.
                0.24D,
                RaiderCombatStyle.RIFLE,
                5.5D * 5.5D,
                145.0D
        );
    }

    /** SMG: long sprays, fast cadence, horizontal walk pattern. */
    public static RaiderWeaponProfile smg() {
        return new RaiderWeaponProfile(
                "Uzi", "357_Magnum", "Combat_Knife",
                32, 2, 6,
                8, 2, 16,
                8.0D, 8.5D,
                28.0D * 28.0D, 2.25D * 2.25D, 3.25D * 3.25D,
                4.5D, 9,
                1, 8.0D, 35L, 18L, 0.50D,
                0.24D,
                RaiderCombatStyle.CLOSE_ASSAULT,
                8.0D * 8.0D,
                165.0D
        );
    }

    /** Shotgun breacher: twin-slug pulse, wide flat cone, push hard. */
    public static RaiderWeaponProfile breacher() {
        return new RaiderWeaponProfile(
                "Origin_12", "357_Magnum", "Combat_Knife",
                8, 3, 6,
                2, 3, 36,
                13.0D, 8.5D,
                18.0D * 18.0D, 2.0D * 2.0D, 3.5D * 3.5D,
                7.5D, 11,
                2, 15.0D, 45L, 22L, 0.60D,
                0.24D,
                RaiderCombatStyle.BREACHER,
                11.0D * 11.0D,
                175.0D
        );
    }

    /** Pistol: triple-tap cadence, accurate opener then opens up. */
    public static RaiderWeaponProfile pistol() {
        return new RaiderWeaponProfile(
                "357_Magnum", "357_Magnum", "Combat_Knife",
                6, 3, 6,
                3, 6, 22,
                6.0D, 6.0D,
                24.0D * 24.0D, 2.25D * 2.25D, 3.25D * 3.25D,
                4.5D, 10,
                1, 8.0D, 35L, 18L, 0.50D,
                0.24D,
                RaiderCombatStyle.CLOSE_ASSAULT,
                7.5D * 7.5D,
                160.0D
        );
    }

    /** Knife-only rusher — never ENGAGE with a gun. */
    public static RaiderWeaponProfile knifeRusher() {
        return new RaiderWeaponProfile(
                "Combat_Knife", "Combat_Knife", "Combat_Knife",
                0, 0, 0,
                0, 20, 40,
                0.0D, 0.0D,
                12.0D * 12.0D, 1.5D * 1.5D, 3.75D * 3.75D,
                9.0D, 7,
                0, 0.0D, 0L, 12L, 0.40D,
                0.24D,
                RaiderCombatStyle.KNIFE_RUSHER,
                14.0D * 14.0D,
                180.0D
        );
    }
}
