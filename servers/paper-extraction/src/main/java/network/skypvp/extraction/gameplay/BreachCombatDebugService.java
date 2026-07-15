package network.skypvp.extraction.gameplay;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.item.ExtractionCombatDefense;
import network.skypvp.extraction.item.ArmorMark;
import network.skypvp.extraction.item.InfuseArmorPayload;
import network.skypvp.extraction.item.InfuseChestplateDefinition;
import network.skypvp.extraction.item.ShieldCombatService;
import network.skypvp.extraction.item.ShieldSocketReference;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player combat debug ring buffer. Recording is gated behind {@code skypvp.combat.debug} so raid combat
 * stays on the hot path with no extra allocations for normal players.
 */
public final class BreachCombatDebugService {

    private static final int MAX_ENTRIES = 48;
    private static final String TRACK_PERMISSION = "skypvp.combat.debug";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final PaperCorePlugin core;
    private final Map<UUID, Deque<CombatLogEntry>> logs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCapture> pendingCaptures = new ConcurrentHashMap<>();

    public BreachCombatDebugService(PaperCorePlugin core) {
        this.core = core;
    }

    public boolean shouldTrack(Player player) {
        return player != null && player.hasPermission(TRACK_PERMISSION);
    }

    public void stageAdjustment(
            Player victim,
            ExtractionCombatDefense.DamageAdjustment adjustment,
            ShieldCombatService.ShieldOutcome shieldOutcome
    ) {
        if (!shouldTrack(victim) || adjustment == null) {
            return;
        }
        pendingCaptures.put(
                victim.getUniqueId(),
                new PendingCapture(adjustment, summarizeEquippedDefense(core, victim), shieldOutcome)
        );
    }

    public PendingCapture consumePending(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return pendingCaptures.remove(playerId);
    }

    public void discardPending(UUID playerId) {
        if (playerId != null) {
            pendingCaptures.remove(playerId);
        }
    }

    public void recordDamage(
            Player victim,
            BreachInstance instance,
            EntityDamageEvent event,
            String attackerName,
            PendingCapture pending
    ) {
        Objects.requireNonNull(victim, "victim");
        Objects.requireNonNull(event, "event");
        double health = victim.getHealth();
        double absorption = victim.getAbsorptionAmount();
        double finalDamage = Math.max(0.0D, event.getFinalDamage());
        ExtractionCombatDefense.DamageAdjustment adjustment = pending != null
                ? pending.adjustment()
                : null;
        ExtractionCombatDefense.DamageAdjustment resolved = adjustment != null
                ? adjustment
                : new ExtractionCombatDefense.DamageAdjustment(0.0D, 1.0D, finalDamage, finalDamage, 0.0D);
        String itemSummary = pending != null && pending.customItemSummary() != null
                ? pending.customItemSummary()
                : summarizeEquippedDefense(core, victim);
        ShieldCombatService.ShieldOutcome shield = pending != null ? pending.shieldOutcome() : null;
        append(victim.getUniqueId(), new CombatLogEntry(
                CombatLogEntry.Kind.DAMAGE,
                System.currentTimeMillis(),
                breachLabel(instance),
                event.getCause(),
                attackerName,
                resolved.damageBefore(),
                finalDamage,
                resolved.infuseAbsorbed(),
                resolved.defenseFraction(),
                resolved.damageMultiplier(),
                health,
                absorption,
                Math.max(0.0D, health + absorption - finalDamage),
                BreachFatalDamageMath.wouldEliminate(victim, event),
                itemSummary,
                false,
                shield == null ? 0.0D : shield.absorbed(),
                shieldStateLabel(shield)
        ));
    }

    public void recordElimination(
            Player victim,
            BreachInstance instance,
            EntityDamageEvent event,
            String attackerName,
            PendingCapture pending
    ) {
        Objects.requireNonNull(victim, "victim");
        Objects.requireNonNull(event, "event");
        double health = victim.getHealth();
        double absorption = victim.getAbsorptionAmount();
        double lethalDamage = Math.max(0.0D, event.getFinalDamage());
        ExtractionCombatDefense.DamageAdjustment adjustment = pending != null
                ? pending.adjustment()
                : null;
        ExtractionCombatDefense.DamageAdjustment resolved = adjustment != null
                ? adjustment
                : new ExtractionCombatDefense.DamageAdjustment(0.0D, 1.0D, lethalDamage, lethalDamage, 0.0D);
        String itemSummary = pending != null && pending.customItemSummary() != null
                ? pending.customItemSummary()
                : summarizeEquippedDefense(core, victim);
        ShieldCombatService.ShieldOutcome shield = pending != null ? pending.shieldOutcome() : null;
        append(victim.getUniqueId(), new CombatLogEntry(
                CombatLogEntry.Kind.ELIMINATION,
                System.currentTimeMillis(),
                breachLabel(instance),
                event.getCause(),
                attackerName,
                resolved.damageBefore(),
                lethalDamage,
                resolved.infuseAbsorbed(),
                resolved.defenseFraction(),
                resolved.damageMultiplier(),
                health,
                absorption,
                0.0D,
                true,
                itemSummary,
                true,
                shield == null ? 0.0D : shield.absorbed(),
                shieldStateLabel(shield)
        ));
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        logs.remove(playerId);
        pendingCaptures.remove(playerId);
    }

    public List<CombatLogEntry> recentLogs(UUID playerId, int limit) {
        if (playerId == null) {
            return List.of();
        }
        Deque<CombatLogEntry> deque = logs.get(playerId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, MAX_ENTRIES));
        List<CombatLogEntry> result = new ArrayList<>(Math.min(capped, deque.size()));
        int skipped = Math.max(0, deque.size() - capped);
        int index = 0;
        for (CombatLogEntry entry : deque) {
            if (index++ < skipped) {
                continue;
            }
            result.add(entry);
        }
        return result;
    }

    public String formatEntry(CombatLogEntry entry) {
        String time = TIME_FORMAT.format(Instant.ofEpochMilli(entry.atMillis()));
        String cause = entry.cause() == null ? "UNKNOWN" : entry.cause().name();
        String attacker = entry.attackerName() == null || entry.attackerName().isBlank()
                ? "environment"
                : entry.attackerName();
        String defense = String.format(Locale.ROOT, "%.0f%%", entry.defenseFraction() * 100.0D);
        String absorbed = String.format(Locale.ROOT, "%.2f", entry.infuseAbsorbed());
        String before = String.format(Locale.ROOT, "%.2f", entry.rawDamageBeforeDefense());
        String finalDamage = String.format(Locale.ROOT, "%.2f", entry.finalDamage());
        String vitality = String.format(Locale.ROOT, "%.1f+%.1f", entry.healthBefore(), entry.absorptionBefore());
        String items = entry.customItemSummary() == null || entry.customItemSummary().isBlank()
                ? "none"
                : entry.customItemSummary();
        String shieldSegment = shieldSegment(entry);

        if (entry.kind() == CombatLogEntry.Kind.ELIMINATION) {
            return "<red>[" + time + "] ELIMINATED</red> <gray>" + cause + "</gray> | lethal "
                    + finalDamage + " HP (pre-defense " + before + ", absorbed " + absorbed + " / " + defense
                    + ")" + shieldSegment + " | vitality " + vitality + " | from " + attacker + " | " + entry.breachLabel()
                    + " | <yellow>" + items + "</yellow>";
        }
        String lethalFlag = entry.wouldEliminate() ? " <gold>(would eliminate)</gold>" : "";
        return "<gray>[" + time + "] HIT</gray> <white>" + cause + "</white> | -" + finalDamage
                + " HP (pre-defense " + before + ", absorbed " + absorbed + " / " + defense + ")"
                + shieldSegment + lethalFlag + " | vitality " + vitality + " → "
                + String.format(Locale.ROOT, "%.1f", entry.estimatedVitalityAfter()) + " | from " + attacker
                + " | " + entry.breachLabel() + " | <yellow>" + items + "</yellow>";
    }

    private static String shieldSegment(CombatLogEntry entry) {
        if (entry.shieldState() == null || entry.shieldState().isBlank()) {
            return "";
        }
        String absorbed = String.format(Locale.ROOT, "%.2f", entry.shieldAbsorbed());
        return " <aqua>| shield -" + absorbed + " [" + entry.shieldState() + "]</aqua>";
    }

    private static String shieldStateLabel(ShieldCombatService.ShieldOutcome shield) {
        if (shield == null || !shield.present()) {
            return "";
        }
        if (shield.destroyedThisHit()) {
            return "DESTROYED";
        }
        if (shield.destroyed()) {
            return "destroyed";
        }
        if (shield.depletedThisHit()) {
            return "DEPLETED";
        }
        return String.format(Locale.ROOT, "%.1f/%.1f", shield.currentPoints(), shield.maxPoints());
    }

    public static String summarizeEquippedDefense(PaperCorePlugin core, Player player) {
        if (player == null) {
            return "none";
        }
        double fraction = core == null ? 0.0D : ExtractionCombatDefense.defenseFraction(core, player);
        CustomItemService service = core == null ? null : core.customItemService();
        if (service == null) {
            return fraction > 0.0D
                    ? String.format(Locale.ROOT, "%.0f%% defense (no item service)", fraction * 100.0D)
                    : "none";
        }

        ItemStack chest = player.getInventory().getChestplate();
        if (service.isCustomItem(chest)) {
            return service.resolve(chest)
                    .map(instance -> {
                        if (!InfuseChestplateDefinition.TYPE_ID.equals(instance.typeId())) {
                            return instance.typeId().uid() + " (active " + Math.round(fraction * 100.0D) + "% defense)";
                        }
                        InfuseArmorPayload payload = InfuseArmorPayload.decode(instance.payloadCopy());
                        ArmorMark mark = payload.mark() == null ? ArmorMark.MK1 : payload.mark();
                        StringBuilder summary = new StringBuilder("infuse_chestplate ")
                                .append(payload.rarity().name())
                                .append(' ')
                                .append(mark.name())
                                .append(" (base ")
                                .append(Math.round(payload.rarity().defensePercent() * 100.0D))
                                .append("%, active ")
                                .append(Math.round(fraction * 100.0D))
                                .append("%)");
                        ShieldSocketReference.decode(payload.shieldModule()).ifPresent(shield -> {
                            summary.append(", shield ").append(shield.displayLabel());
                            if (shield.destroyed()) {
                                summary.append(" DESTROYED");
                            } else {
                                summary.append(" ")
                                        .append(String.format(Locale.ROOT, "%.1f/%.1f", shield.currentPoints(), shield.maxPoints()))
                                        .append(" int ")
                                        .append(String.format(Locale.ROOT, "%.0f/%.0f", shield.remainingIntegrity(), shield.integrity()));
                            }
                        });
                        return summary.toString();
                    })
                    .orElseGet(() -> fraction > 0.0D
                            ? Math.round(fraction * 100.0D) + "% defense"
                            : "none");
        }
        if (fraction > 0.0D) {
            return Math.round(fraction * 100.0D) + "% defense";
        }
        return "none";
    }

    private void append(UUID playerId, CombatLogEntry entry) {
        Deque<CombatLogEntry> deque = logs.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(entry);
            while (deque.size() > MAX_ENTRIES) {
                deque.removeFirst();
            }
        }
    }

    private static String breachLabel(BreachInstance instance) {
        if (instance == null) {
            return "no-breach";
        }
        return instance.mapMeta().displayName() + " (" + instance.instanceId() + ")";
    }

    public record PendingCapture(
            ExtractionCombatDefense.DamageAdjustment adjustment,
            String customItemSummary,
            ShieldCombatService.ShieldOutcome shieldOutcome
    ) {
    }

    public record CombatLogEntry(
            Kind kind,
            long atMillis,
            String breachLabel,
            EntityDamageEvent.DamageCause cause,
            String attackerName,
            double rawDamageBeforeDefense,
            double finalDamage,
            double infuseAbsorbed,
            double defenseFraction,
            double damageMultiplier,
            double healthBefore,
            double absorptionBefore,
            double estimatedVitalityAfter,
            boolean wouldEliminate,
            String customItemSummary,
            boolean cancelled,
            double shieldAbsorbed,
            String shieldState
    ) {
        enum Kind {
            DAMAGE,
            ELIMINATION
        }
    }
}
