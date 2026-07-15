package network.skypvp.extraction.item;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Drives shield buffer recharge. Each recharger carries a fixed charge (its restore amount); drinking it delivers up
 * to that many shield points, never exceeding the shield's capacity. The recharger's tier also sets the pace: lower
 * tiers deliver the charge gradually over time, the top tier applies it instantly. A shield's rarity still governs its
 * capacity and lifetime integrity.
 */
public final class ShieldRechargeService {

    private static final long PERIOD_TICKS = 5L;
    private static final double PERIOD_SECONDS = PERIOD_TICKS / 20.0D;
    private static final double EPSILON = 0.0001D;

    public enum Outcome {
        STARTED,
        INSTANT,
        NO_ARMOR,
        NO_SHIELD,
        DESTROYED,
        ALREADY_FULL,
        ALREADY_RECHARGING
    }

    private static final class RechargeState {
        private final double perTick;
        private double remaining;
        private PlatformTask task;

        private RechargeState(double remaining, double perTick) {
            this.remaining = remaining;
            this.perTick = perTick;
        }
    }

    private final PaperCorePlugin core;
    private final ServerPlatform platform;
    private final Map<UUID, RechargeState> active = new ConcurrentHashMap<>();

    public ShieldRechargeService(PaperCorePlugin core, ServerPlatform platform) {
        this.core = Objects.requireNonNull(core, "core");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public Outcome beginRecharge(Player player, RechargerTier tier) {
        CustomItemService service = core.customItemService();
        if (service == null || player == null) {
            return Outcome.NO_ARMOR;
        }
        // A shield can only be fed by one recharger at a time: reject any further recharger use while a gradual
        // recharge is still draining its charge into the buffer.
        if (active.containsKey(player.getUniqueId())) {
            return Outcome.ALREADY_RECHARGING;
        }
        RechargerTier resolved = tier == null ? RechargerTier.FIELD : tier;

        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            return Outcome.NO_ARMOR;
        }
        Optional<ShieldSocketReference> refOpt = readShield(service, armor);
        if (refOpt.isEmpty()) {
            return Outcome.NO_SHIELD;
        }
        ShieldSocketReference ref = refOpt.get();
        if (ref.destroyed()) {
            return Outcome.DESTROYED;
        }
        if (ref.currentPoints() >= ref.maxPoints()) {
            return Outcome.ALREADY_FULL;
        }

        cancel(player.getUniqueId());

        if (resolved.instant()) {
            double added = Math.min(resolved.rechargeAmount(), ref.maxPoints() - ref.currentPoints());
            ShieldSocketReference updated = ref.withState(ref.currentPoints() + added, ref.lifetimeAbsorbed(), false);
            writeShield(service, player, armor, updated);
            ShieldFeedback.recharged(player);
            return Outcome.INSTANT;
        }

        double perTick = Math.max(0.05D, resolved.pointsPerSecond() * PERIOD_SECONDS);
        UUID playerId = player.getUniqueId();
        RechargeState state = new RechargeState(resolved.rechargeAmount(), perTick);
        active.put(playerId, state);
        ShieldFeedback.rechargeStarted(player);
        PlatformTask task = platform.runOnPlayerTimer(
                player,
                () -> tick(playerId),
                PERIOD_TICKS,
                PERIOD_TICKS
        );
        state.task = task;
        return Outcome.STARTED;
    }

    private void tick(UUID playerId) {
        RechargeState state = active.get(playerId);
        if (state == null) {
            return;
        }
        CustomItemService service = core.customItemService();
        Player player = service == null ? null : core.getServer().getPlayer(playerId);
        if (service == null || player == null || !player.isOnline()) {
            cancel(playerId);
            return;
        }
        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            cancel(playerId);
            return;
        }
        Optional<ShieldSocketReference> refOpt = readShield(service, armor);
        if (refOpt.isEmpty()) {
            cancel(playerId);
            return;
        }
        ShieldSocketReference ref = refOpt.get();
        if (ref.destroyed()) {
            cancel(playerId);
            return;
        }

        double headroom = ref.maxPoints() - ref.currentPoints();
        double deliver = Math.min(state.perTick, Math.min(state.remaining, headroom));
        if (deliver > EPSILON) {
            double newPoints = ref.currentPoints() + deliver;
            ShieldSocketReference updated = ref.withState(newPoints, ref.lifetimeAbsorbed(), false);
            writeShield(service, player, armor, updated);
            state.remaining -= deliver;
        }

        boolean full = ref.maxPoints() - (ref.currentPoints() + Math.max(0.0D, deliver)) <= EPSILON;
        if (state.remaining <= EPSILON || full) {
            cancel(playerId);
            ShieldFeedback.recharged(player);
        } else {
            ShieldFeedback.rechargeTick(player);
        }
    }

    public void cancel(UUID playerId) {
        RechargeState state = active.remove(playerId);
        if (state != null && state.task != null) {
            state.task.cancel();
        }
    }

    public void shutdown() {
        active.values().forEach(state -> {
            if (state.task != null) {
                state.task.cancel();
            }
        });
        active.clear();
    }

    public String describe(Outcome outcome, RechargerTier tier) {
        RechargerTier resolved = tier == null ? RechargerTier.FIELD : tier;
        return switch (outcome) {
            case STARTED -> "<aqua>Shield recharging <gray>(+" + resolved.amountLabel()
                    + " @ " + resolved.rateLabel() + ").";
            case INSTANT -> "<aqua>Shield restored <gray>(+" + resolved.amountLabel() + ")<aqua> instantly.";
            case NO_ARMOR -> "<red>Wear an Infuse chestplate to recharge its shield.";
            case NO_SHIELD -> "<red>No shield module is socketed.";
            case DESTROYED -> "<red>Shield is destroyed — repair it at the armory.";
            case ALREADY_FULL -> "<red>Shield is already at full charge.";
            case ALREADY_RECHARGING -> "<red>A recharger is already charging your shield — wait for it to finish.";
        };
    }

    private static Optional<ShieldSocketReference> readShield(CustomItemService service, ItemStack armor) {
        return service.resolve(armor)
                .map(instance -> InfuseArmorPayload.decode(instance.payloadCopy()))
                .flatMap(payload -> ShieldSocketReference.decode(payload.shieldModule()));
    }

    private static void writeShield(CustomItemService service, Player player, ItemStack armor, ShieldSocketReference shield) {
        InfuseArmorPayload current = service.resolve(armor)
                .map(instance -> InfuseArmorPayload.decode(instance.payloadCopy()))
                .orElse(null);
        if (current == null) {
            return;
        }
        InfuseArmorPayload updated = current.withShield(shield);
        ItemStack updatedArmor = service.updatePayload(armor, ignored -> updated.encode());
        InfuseArmorMutator.writeArmorStack(player, armor, updatedArmor);
    }
}
