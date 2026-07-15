package network.skypvp.extraction.listener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.extraction.crafting.ItemConfigOverrides;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gameplay.BreachStaminaService;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.extraction.item.MedicHealService;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Medic supplies: healing items must be eaten (cast delay), then {@link MedicHealService} delivers HP in
 * tiered bursts. Stamina syringes keep instant right-click use for combat responsiveness.
 */
public final class MedicConsumableListener implements Listener {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final BreachStaminaService staminaService;
    private final MedicHealService healService;
    private final Map<UUID, Map<MedicConsumableType, Long>> lastUseByType = new ConcurrentHashMap<>();

    public MedicConsumableListener(
            PaperCorePlugin core,
            BreachEngine engine,
            BreachStaminaService staminaService,
            MedicHealService healService
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.staminaService = Objects.requireNonNull(staminaService, "staminaService");
        this.healService = Objects.requireNonNull(healService, "healService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!CustomItemUseSupport.isRightClick(event)) {
            return;
        }
        ItemStack stack = CustomItemUseSupport.itemInHand(event);
        Optional<MedicConsumableType> type = medicType(stack);
        if (type.isEmpty()) {
            return;
        }
        // Healing supplies are eatable — do not cancel the interact or the eat cast never starts.
        if (type.get().isHealing()) {
            ensureHealingConsumable(event.getPlayer(), event.getHand(), stack, type.get());
            return;
        }
        tryUseSyringe(event.getPlayer(), type.get(), event.getHand(), () -> CustomItemUseSupport.suppressVanilla(event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Optional<MedicConsumableType> type = medicType(CustomItemUseSupport.itemInHand(event));
        if (type.isEmpty()) {
            return;
        }
        if (type.get().isHealing()) {
            return;
        }
        tryUseSyringe(event.getPlayer(), type.get(), event.getHand(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        Optional<MedicConsumableType> type = medicType(event.getItem());
        if (type.isEmpty()) {
            return;
        }
        // Take over consumption so vanilla hunger/absorption effects never apply.
        event.setCancelled(true);
        Player player = event.getPlayer();
        MedicConsumableType medic = type.get();
        if (medic.isHealing()) {
            useHealing(player, medic, event.getHand());
            return;
        }
        if (medic.isSyringe()) {
            tryUseSyringe(player, medic, event.getHand(), () -> {
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        staminaService.unenroll(event.getPlayer().getUniqueId());
        healService.cancel(event.getPlayer().getUniqueId());
        lastUseByType.remove(event.getPlayer().getUniqueId());
    }

    private void useHealing(Player player, MedicConsumableType type, EquipmentSlot hand) {
        if (!canUseInRaid(player)) {
            send(player, "<red>Medic supplies can only be used during an active breach raid.");
            return;
        }
        long remaining = cooldownRemaining(player, type);
        if (remaining > 0L) {
            send(player, "<red>Wait <white>" + formatCooldown(remaining) + "<red> before using another "
                    + type.displayName() + ".");
            return;
        }
        MedicHealService.Outcome outcome = healService.beginHeal(player, type);
        send(player, healService.describe(outcome, type));
        if (outcome == MedicHealService.Outcome.STARTED) {
            consumeOne(player, hand);
            recordUse(player, type);
        }
    }

    private void tryUseSyringe(Player player, MedicConsumableType type, EquipmentSlot hand, Runnable suppress) {
        if (!type.isSyringe()) {
            return;
        }
        suppress.run();
        if (!canUseInRaid(player)) {
            send(player, "<red>Medic supplies can only be used during an active breach raid.");
            return;
        }
        long remaining = cooldownRemaining(player, type);
        if (remaining > 0L) {
            send(player, "<red>Wait <white>" + formatCooldown(remaining) + "<red> before using another "
                    + type.displayName() + ".");
            return;
        }
        staminaService.applyMedicSyringe(player, type);
        consumeOne(player, hand);
        recordUse(player, type);
        send(player, "<green>Used <white>" + type.displayName() + "<green>.");
    }

    private long cooldownRemaining(Player player, MedicConsumableType type) {
        Map<MedicConsumableType, Long> uses = lastUseByType.get(player.getUniqueId());
        if (uses == null) {
            return 0L;
        }
        Long last = uses.get(type);
        if (last == null) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        return Math.max(0L, ItemConfigOverrides.medicUseCooldownMillis(type) - elapsed);
    }

    private void recordUse(Player player, MedicConsumableType type) {
        lastUseByType.compute(player.getUniqueId(), (id, current) -> {
            Map<MedicConsumableType, Long> uses = current == null ? new ConcurrentHashMap<>() : current;
            uses.put(type, System.currentTimeMillis());
            return uses;
        });
    }

    private static String formatCooldown(long millis) {
        double seconds = millis / 1000.0D;
        if (millis % 1000L == 0L) {
            return (millis / 1000L) + "s";
        }
        return String.format(java.util.Locale.US, "%.1fs", seconds);
    }

    private boolean canUseInRaid(Player player) {
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty() || BreachLobbyProtection.isLobbySafe(engine, player)) {
            return false;
        }
        BreachInstance breach = instance.get();
        return breach.state() == BreachState.ACTIVE
            && breach.containsPlayer(player.getUniqueId())
            && !breach.hasExtracted(player.getUniqueId())
            && !breach.isEliminated(player.getUniqueId())
            && !breach.isPendingJoin(player.getUniqueId())
            && !engine.isSpectating(player);
    }

    private Optional<MedicConsumableType> medicType(ItemStack stack) {
        CustomItemService service = core.customItemService();
        if (service == null || stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
            return Optional.empty();
        }
        return service.resolve(stack).flatMap(instance -> MedicConsumableType.byTypeId(instance.typeId()));
    }

    /** Migrates/overrides medic stacks so healing supplies use the configured eat cast (not vanilla food effects). */
    private void ensureHealingConsumable(Player player, EquipmentSlot hand, ItemStack stack, MedicConsumableType type) {
        float wantedSeconds = ItemConfigOverrides.medicConsumeSeconds(type);
        var food = stack.getData(DataComponentTypes.FOOD);
        var consumable = stack.getData(DataComponentTypes.CONSUMABLE);
        if (food != null
                && food.canAlwaysEat()
                && food.nutrition() == 0
                && consumable != null
                && Math.abs(consumable.consumeSeconds() - wantedSeconds) < 0.05F) {
            return;
        }
        EquipmentSlot slot = hand == null ? EquipmentSlot.HAND : hand;
        ItemStack updated = stack.clone();
        ExtractionCustomItemProvider.applyHealingConsumable(updated, type);
        player.getInventory().setItem(slot, updated);
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        EquipmentSlot slot = hand == null ? EquipmentSlot.HAND : hand;
        ItemStack inHand = player.getInventory().getItem(slot);
        if (inHand == null || inHand.getType().isAir()) {
            return;
        }
        int amount = inHand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItem(slot, null);
        } else {
            inHand.setAmount(amount - 1);
            player.getInventory().setItem(slot, inHand);
        }
    }

    private void send(Player player, String miniMessage) {
        player.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, ExtractionTexts.locale(player)));
    }
}
