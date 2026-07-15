package network.skypvp.paper.quest;

import org.bukkit.Bukkit;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;

/**
 * Keeps quest NPCs under {@link QuestNpcAgent} / {@link network.skypvp.paper.ai.statetree.StateTreeEngine}
 * control by stripping vanilla goal selectors and villager brain walk/look memories.
 *
 * <p>Villagers especially re-register schedule/gossip behaviors that fight scripted pathfinding;
 * {@link Mob#setAware(false)} blocks ambient targeting while still allowing {@code pathfinder.moveTo}.
 */
public final class QuestNpcAiSupport {

    private QuestNpcAiSupport() {
    }

    /** Call on spawn and periodically from the agent tick (entity thread). */
    public static void stripVanillaAi(Mob mob) {
        if (mob == null || !mob.isValid() || mob.isDead()) {
            return;
        }
        try {
            mob.setTarget(null);
        } catch (Throwable ignored) {
        }
        try {
            // Unaware of surroundings (no panic/gossip chase) but pathfinding still works.
            mob.setAware(false);
        } catch (Throwable ignored) {
        }
        try {
            Bukkit.getMobGoals().removeAllGoals(mob);
        } catch (Throwable ignored) {
        }
        if (mob instanceof Villager villager) {
            clearVillagerBrainTargets(villager);
        }
    }

    private static void clearVillagerBrainTargets(Villager villager) {
        // Bukkit exposes POI/home memories; walk/look targets are NMS-only — clear what we can
        // and rely on setAware(false) + removeAllGoals for the rest.
        eraseMemory(villager, MemoryKey.HOME);
        eraseMemory(villager, MemoryKey.JOB_SITE);
        eraseMemory(villager, MemoryKey.POTENTIAL_JOB_SITE);
        eraseMemory(villager, MemoryKey.MEETING_POINT);
        eraseMemory(villager, MemoryKey.HIDING_PLACE);
        eraseMemory(villager, MemoryKey.IS_PANICKING);
        eraseMemory(villager, MemoryKey.CANT_REACH_WALK_TARGET_SINCE);
        eraseByKey(villager, "minecraft:walk_target");
        eraseByKey(villager, "minecraft:look_target");
    }

    private static void eraseByKey(Villager villager, String namespaced) {
        try {
            MemoryKey<?> key = MemoryKey.getByKey(org.bukkit.NamespacedKey.fromString(namespaced));
            if (key != null) {
                eraseMemory(villager, key);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void eraseMemory(Villager villager, MemoryKey<?> key) {
        try {
            villager.setMemory(key, null);
        } catch (Throwable ignored) {
            // Memory key unsupported or immutable on this version — goals strip still applies.
        }
    }
}
