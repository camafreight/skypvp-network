package network.skypvp.extraction.gameplay;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import network.skypvp.extraction.gameplay.BreachHitMarkerService.HitType;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Spawns MMO-style floating damage numbers at the victim that only the attacker can see (personal HUD info, so it
 * never clutters other players' screens). Each number is a short-lived, per-viewer {@link TextDisplay} that floats
 * upward via transformation interpolation and is then removed.
 *
 * <p>All entity work happens on the victim's region thread (the caller is inside the damage event), and the delayed
 * animation/cleanup is re-scheduled onto that same chunk so it stays Folia-safe.</p>
 */
public final class BreachDamageIndicatorService {

    private static final int LIFESPAN_TICKS = 22;
    private static final float RISE_BLOCKS = 0.9F;
    /**
     * Minimum gap between spawned indicators for the same attacker→victim pair. Automatic fire would otherwise spawn
     * (and remove) a fresh {@link TextDisplay} entity every ~100ms per target, which floods clients with entity
     * spawn/metadata/remove packets. Crit/headshot numbers bypass this so key hits are never dropped.
     */
    private static final long MIN_INTERVAL_MS = 130L;

    private final JavaPlugin plugin;
    private final PaperCorePlugin core;
    private final Map<Long, Long> lastShownByPair = new ConcurrentHashMap<>();

    public BreachDamageIndicatorService(JavaPlugin plugin, PaperCorePlugin core) {
        this.plugin = plugin;
        this.core = core;
    }

    public void show(Player attacker, LivingEntity victim, double amount, HitType type) {
        if (attacker == null || victim == null || !victim.isValid() || victim.isDead()
                || !attacker.isOnline() || amount <= 0.0D || type == null) {
            return;
        }
        boolean important = type == HitType.HEADSHOT || type == HitType.CRIT;
        if (!important && isThrottled(attacker, victim)) {
            return;
        }
        if (this.core != null
                && this.core.clientUpdatePipeline() != null
                && !important
                && !this.core.clientUpdatePipeline().tryAcquire(
                        network.skypvp.paper.clientupdate.UpdateChannel.DISPLAY_FX,
                        1)) {
            return;
        }
        World world = victim.getWorld();
        if (world == null) {
            return;
        }
        double jitterX = ThreadLocalRandom.current().nextDouble(-0.35D, 0.35D);
        double jitterZ = ThreadLocalRandom.current().nextDouble(-0.35D, 0.35D);
        Location loc = victim.getLocation().add(jitterX, victim.getHeight() + 0.35D, jitterZ);
        spawn(attacker, world, loc, format(amount, type), type);
    }

    private void spawn(Player attacker, World world, Location loc, Component text, HitType type) {
        boolean emphasized = type == HitType.CRIT || type == HitType.HEADSHOT;
        float scale = emphasized ? 1.35F : 1.0F;
        TextDisplay display = world.spawn(loc, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setVisibleByDefault(false);
            d.setSeeThrough(true);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setViewRange(0.5F);
            d.setPersistent(false);
            d.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
        attacker.showEntity(plugin, display);

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        core.platformScheduler().runAtChunkLater(world, chunkX, chunkZ, () -> {
            if (!display.isValid()) {
                return;
            }
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(LIFESPAN_TICKS);
            Transformation current = display.getTransformation();
            display.setTransformation(new Transformation(
                    new Vector3f(0.0F, RISE_BLOCKS, 0.0F),
                    current.getLeftRotation(),
                    current.getScale(),
                    current.getRightRotation()));
        }, 1L);
        core.platformScheduler().runAtChunkLater(world, chunkX, chunkZ, () -> {
            if (display.isValid()) {
                display.remove();
            }
        }, LIFESPAN_TICKS + 3L);
    }

    private boolean isThrottled(Player attacker, LivingEntity victim) {
        long now = System.currentTimeMillis();
        long key = (((long) attacker.getUniqueId().hashCode()) << 32)
                ^ (victim.getUniqueId().hashCode() & 0xFFFFFFFFL);
        Long previous = lastShownByPair.put(key, now);
        return previous != null && (now - previous) < MIN_INTERVAL_MS;
    }

    private static Component format(double amount, HitType type) {
        String number = amount == Math.floor(amount)
                ? String.valueOf((long) amount)
                : String.format(Locale.US, "%.1f", amount);
        return switch (type) {
            case SHIELD -> plain("\u2749 " + number, NamedTextColor.AQUA, false);
            case HEADSHOT -> plain("\u2620 " + number, NamedTextColor.GOLD, true);
            case CRIT -> plain("\u2739 " + number, NamedTextColor.YELLOW, true);
            case HEALTH -> plain(number, NamedTextColor.RED, false);
        };
    }

    private static Component plain(String text, NamedTextColor color, boolean bold) {
        return Component.text(text, color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, bold);
    }
}
