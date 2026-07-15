package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Applies a field shield repair kit during an active breach raid. */
public final class ShieldRepairKitListener implements Listener {

    private final PaperCorePlugin core;
    private final BreachEngine engine;

    public ShieldRepairKitListener(PaperCorePlugin core, BreachEngine engine) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!CustomItemUseSupport.isRightClick(event)) {
            return;
        }
        tryUse(event.getPlayer(), CustomItemUseSupport.itemInHand(event), event.getHand(), () -> {
            CustomItemUseSupport.suppressVanilla(event);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        tryUse(event.getPlayer(), CustomItemUseSupport.itemInHand(event), event.getHand(), () -> {
            event.setCancelled(true);
        });
    }

    private void tryUse(Player player, ItemStack stack, EquipmentSlot hand, Runnable suppress) {
        CustomItemService service = core.customItemService();
        if (service == null || !InfuseArmorMutator.isShieldRepairKit(service, stack)) {
            return;
        }
        suppress.run();
        if (!canUseInRaid(player)) {
            send(player, "<red>Shield repair kits can only be used during an active breach raid.");
            return;
        }
        InfuseArmorMutator.FieldRepairOutcome outcome = InfuseArmorMutator.fieldRepairSocketedShield(service, player);
        switch (outcome) {
            case NO_CHESTPLATE -> send(player, "<red>Wear an Infuse chestplate with a socketed shield.");
            case NO_SHIELD -> send(player, "<red>Your chestplate has no socketed shield.");
            case ALREADY_OK -> send(player, "<yellow>Your shield does not need emergency repair.");
            case REPAIRED -> {
                consumeOne(player, hand);
                send(player, "<green>Field repair applied — shield is back online.");
            }
        }
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
