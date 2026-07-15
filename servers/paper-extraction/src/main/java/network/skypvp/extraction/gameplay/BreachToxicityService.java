package network.skypvp.extraction.gameplay;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.clientupdate.UpdateChannel;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Toxic air visuals during the final minutes and lethal damage during the toxic phase.
 *
 * <p>Each viewer's creep cloud is personal (anchored at their eyes) and is emitted on that
 * player's region thread via {@code player.spawnParticle}, budgeted through the
 * ClientUpdatePipeline. It previously used {@code world.spawnParticle(..., force=true)} from
 * one region thread: an off-region world access for viewers in other Folia regions, a forced
 * broadcast that duplicated every nearby viewer's cloud to everyone else, and a bypass of the
 * pipeline's particle budget — a triple source of cross-region spikes.
 */
public final class BreachToxicityService {

    private final BreachConfigService configService;
    private final ServerPlatform scheduler;
    private final PaperCorePlugin core;

    public BreachToxicityService(BreachConfigService configService, ServerPlatform scheduler, PaperCorePlugin core) {
        this.configService = configService;
        this.scheduler = scheduler;
        this.core = core;
    }

    public void tickCreep(BreachInstance instance, List<Player> viewers) {
        if (instance == null || viewers == null || viewers.isEmpty()) {
            return;
        }
        World world = instance.world();
        if (world == null) {
            return;
        }
        int remaining = instance.remainingSeconds();
        int forceClose = configService.extractForceCloseSeconds();
        if (remaining > forceClose || remaining <= 0) {
            return;
        }
        double progress = 1.0D - (remaining / (double) forceClose);
        int particleCount = Math.max(6, (int) Math.round(12 + progress * 48));
        for (Player player : viewers) {
            if (player == null || !player.isOnline() || !player.getWorld().equals(world)) {
                continue;
            }
            runForPlayer(player, () -> {
                if (tryParticleBudget(Math.max(1, particleCount / 4))) {
                    spawnCreepParticles(player, ThreadLocalRandom.current(), particleCount, progress);
                }
            });
        }
    }

    public void tickLethal(BreachInstance instance, List<Player> liveRaiders) {
        if (instance == null || liveRaiders == null || liveRaiders.isEmpty()) {
            return;
        }
        for (Player player : liveRaiders) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            runForPlayer(player, () -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                if (random.nextInt(3) == 0 && trySoundBudget()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_HUSK_AMBIENT, SoundCategory.HOSTILE, 0.85F, 0.75F);
                }
                if (random.nextInt(5) == 0 && trySoundBudget()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_HUSK_HURT, SoundCategory.HOSTILE, 0.55F, 0.65F);
                }
                if (tryParticleBudget(5)) {
                    spawnCreepParticles(player, random, 18, 1.0D);
                }
            });
        }
    }

    public double damageAmount() {
        return configService.toxicityDamageAmount();
    }

    private void runForPlayer(Player player, Runnable task) {
        if (scheduler != null) {
            scheduler.runOnPlayer(player, task);
        } else {
            task.run();
        }
    }

    private boolean tryParticleBudget(int units) {
        return core == null
                || core.clientUpdatePipeline() == null
                || core.clientUpdatePipeline().tryAcquire(UpdateChannel.PARTICLE, units);
    }

    private boolean trySoundBudget() {
        return core == null
                || core.clientUpdatePipeline() == null
                || core.clientUpdatePipeline().tryAcquire(UpdateChannel.SOUND, 1);
    }

    /** Personal cloud: only this player receives the packets, and clients may cull it (no force). */
    private void spawnCreepParticles(Player player, ThreadLocalRandom random, int count, double intensity) {
        var eye = player.getEyeLocation();
        double radius = 6.0D + intensity * 18.0D;
        double height = 4.0D + intensity * 10.0D;
        Particle.DustOptions dust = new Particle.DustOptions(
                Color.fromRGB(120, 40, 180),
                (float) (1.0D + intensity * 1.5D)
        );
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(Math.PI * 2.0D);
            double dist = random.nextDouble(radius);
            double x = eye.getX() + Math.cos(angle) * dist;
            double z = eye.getZ() + Math.sin(angle) * dist;
            double y = eye.getY() + random.nextDouble(-2.0D, height);
            player.spawnParticle(
                    Particle.DUST,
                    x,
                    y,
                    z,
                    0,
                    (random.nextDouble() - 0.5D) * 0.04D,
                    -0.02D - intensity * 0.03D,
                    (random.nextDouble() - 0.5D) * 0.04D,
                    0.0D,
                    dust,
                    false
            );
        }
    }
}
