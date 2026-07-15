package network.skypvp.extraction.ai.raider;

import network.skypvp.extraction.gameplay.MythicMobHealthUtil;
import network.skypvp.extraction.integration.WeaponMechanicsCombatBridge;
import network.skypvp.paper.ai.navigation.MobNavigationSupport;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Melee strikes and medic heals for Ruins gunner AI. */
final class RaiderCombatSupport {

    private static final double MELEE_CHASE_SPEED = 1.35D;
    private static final long MELEE_CHASE_REFRESH_TICKS = 4L;

    private RaiderCombatSupport() {
    }

    static void tickMeleeChase(RaiderAgentContext ctx, LivingEntity target) {
        long tick = ctx.aiTick;
        if (tick - ctx.lastMeleeChaseTick < MELEE_CHASE_REFRESH_TICKS) {
            return;
        }
        ctx.lastMeleeChaseTick = tick;
        // Track the living entity so pathfinding keeps updating while the player stands still.
        // A fixed offset waypoint "arrives" and freezes the mob until the player moves again.
        if (MobNavigationSupport.navigateToEntity(ctx, target, MELEE_CHASE_SPEED)) {
            nudgeTowardTarget(ctx, target);
            return;
        }
        Location feet = target.getLocation().clone();
        double angle = ((ctx.entity.getUniqueId().getLeastSignificantBits() & 0xFFL) / 255.0D) * Math.PI * 2.0D;
        feet.add(Math.cos(angle) * 1.2D, 0.0D, Math.sin(angle) * 1.2D);
        MobNavigationSupport.navigateTo(ctx, feet, MELEE_CHASE_SPEED);
        nudgeTowardTarget(ctx, target);
    }

    static void stopMeleeChase(RaiderAgentContext ctx) {
        ctx.mob.getPathfinder().stopPathfinding();
        ctx.lastMeleeChaseTick = 0L;
        MobNavigationSupport.resetProgress(ctx);
    }

    static boolean strike(
            RaiderAgentContext ctx,
            LivingEntity target,
            WeaponMechanicsCombatBridge combat
    ) {
        long tick = ctx.aiTick;
        if (!RaiderSightSupport.canStrikeTarget(ctx, target)) {
            return false;
        }
        if (tick < ctx.nextMeleeTick) {
            return false;
        }
        if (target instanceof Player player
                && ctx.playerTargetGate != null
                && !ctx.playerTargetGate.allows(player)) {
            return false;
        }
        ctx.nextMeleeTick = tick + ctx.profile.meleeCooldownTicks();
        combat.playMeleeSwing(ctx.entity);
        final double damage = ctx.profile.meleeDamage();
        final String weapon = ctx.profile.meleeWeapon();
        if (target instanceof Player player && ctx.combatPlatform != null) {
            ctx.combatPlatform.runOnPlayer(player, () -> {
                if (!player.isValid() || player.isDead()) {
                    return;
                }
                if (ctx.playerTargetGate != null && !ctx.playerTargetGate.allows(player)) {
                    return;
                }
                combat.meleeStrike(ctx.entity, player, weapon, damage);
            });
        } else if (!combat.meleeStrike(ctx.entity, target, weapon, damage)) {
            return false;
        }
        ctx.entity.getWorld().playSound(
                ctx.entity.getLocation(),
                Sound.ENTITY_PLAYER_ATTACK_STRONG,
                0.85F,
                0.95F + (float) Math.random() * 0.1F
        );
        return true;
    }

    private static void nudgeTowardTarget(RaiderAgentContext ctx, LivingEntity target) {
        if (RaiderSightSupport.withinStrikeRange(ctx, target)) {
            return;
        }
        Location origin = ctx.entity.getLocation();
        Location destination = target.getLocation();
        Vector delta = destination.toVector().subtract(origin.toVector());
        double horizontalSq = delta.getX() * delta.getX() + delta.getZ() * delta.getZ();
        if (horizontalSq < 0.04D && Math.abs(delta.getY()) < 0.25D) {
            return;
        }
        Vector push = new Vector(delta.getX(), 0.0D, delta.getZ());
        if (push.lengthSquared() > 0.04D) {
            push.normalize().multiply(0.12D);
        } else {
            push.zero();
        }
        if (Math.abs(delta.getY()) > 0.35D && horizontalSq <= ctx.profile.meleeRangeSq()) {
            push.setY(Math.copySign(0.10D, delta.getY()));
        }
        if (push.lengthSquared() > 0.0D) {
            ctx.entity.setVelocity(push);
        }
    }

    static void beginHeal(RaiderAgentContext ctx) {
        ctx.healCompleteTick = ctx.aiTick + ctx.profile.healTicks();
        ctx.entity.getWorld().playSound(ctx.entity.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7F, 1.15F);
    }

    static void completeHeal(RaiderAgentContext ctx) {
        long tick = ctx.aiTick;
        double beforeRatio = MythicMobHealthUtil.snapshot(ctx.entity).ratio();
        MythicMobHealthUtil.applyHeal(ctx.entity, ctx.profile.healAmount());
        ctx.healsRemaining = Math.max(0, ctx.healsRemaining - 1);
        ctx.healGraceUntilTick = tick + RaiderAgentContext.HEAL_GRACE_TICKS;
        double afterRatio = MythicMobHealthUtil.snapshot(ctx.entity).ratio();
        if (afterRatio <= beforeRatio + 0.01D && ctx.healsRemaining > 0) {
            ctx.healsRemaining = 0;
        }
        ctx.entity.getWorld().playSound(ctx.entity.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55F, 1.35F);
    }
}