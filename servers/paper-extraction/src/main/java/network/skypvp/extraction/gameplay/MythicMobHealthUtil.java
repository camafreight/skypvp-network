package network.skypvp.extraction.gameplay;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.Optional;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/** Resolves display health for MythicMobs (Bukkit HP often stays pinned while Mythic tracks real damage). */
public final class MythicMobHealthUtil {

    private MythicMobHealthUtil() {
    }

    public static HealthSnapshot snapshot(LivingEntity entity) {
        if (entity == null) {
            return HealthSnapshot.empty();
        }
        Optional<ActiveMob> activeMob = resolveActiveMob(entity);
        if (activeMob.isPresent()) {
            ActiveMob mob = activeMob.get();
            double max = Math.max(1.0D, resolveMaxHealth(mob, entity));
            double current = Math.max(0.0D, Math.min(max, resolveCurrentHealth(mob, entity, max)));
            return new HealthSnapshot(current, max);
        }
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? 20.0D : attribute.getValue();
        double current = Math.max(0.0D, Math.min(max, entity.getHealth()));
        return new HealthSnapshot(current, max);
    }

    private static Optional<ActiveMob> resolveActiveMob(LivingEntity entity) {
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            return Optional.empty();
        }
        try {
            return MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static double resolveCurrentHealth(ActiveMob mob, LivingEntity entity, double max) {
        AbstractEntity abstractEntity = mob.getEntity();
        if (abstractEntity != null) {
            return Math.max(0.0D, Math.min(max, abstractEntity.getHealth()));
        }
        return Math.max(0.0D, Math.min(max, entity.getHealth()));
    }

    private static double resolveMaxHealth(ActiveMob mob, LivingEntity entity) {
        AbstractEntity abstractEntity = mob.getEntity();
        if (abstractEntity != null) {
            double max = abstractEntity.getMaxHealth();
            if (max > 0.0D) {
                return max;
            }
        }
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null && attribute.getValue() > 0.0D) {
            return attribute.getValue();
        }
        return Math.max(1.0D, entity.getHealth());
    }

    public static void applyHeal(LivingEntity entity, double amount) {
        if (entity == null || amount <= 0.0D) {
            return;
        }
        HealthSnapshot snapshot = snapshot(entity);
        double healed = Math.min(snapshot.max(), snapshot.current() + amount);
        resolveActiveMob(entity).ifPresent(mob -> {
            AbstractEntity abstractEntity = mob.getEntity();
            if (abstractEntity != null) {
                abstractEntity.setHealth(healed);
            }
        });
        entity.setHealth(Math.max(0.01D, Math.min(healed, entity.getMaxHealth())));
    }

    public record HealthSnapshot(double current, double max) {
        public static HealthSnapshot empty() {
            return new HealthSnapshot(0.0D, 1.0D);
        }

        public double ratio() {
            return max <= 0.0D ? 0.0D : current / max;
        }
    }
}
