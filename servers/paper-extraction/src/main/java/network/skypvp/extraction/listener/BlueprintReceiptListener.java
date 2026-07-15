package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.crafting.BlueprintDefinition;
import network.skypvp.extraction.crafting.BlueprintDiscoveryService;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.item.BlueprintReceiptDefinition;
import network.skypvp.extraction.item.BlueprintReceiptPayload;
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

/** Consumes blueprint receipt items to unlock craft workbench recipes. */
public final class BlueprintReceiptListener implements Listener {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingConfigService craftingConfig;
    private final BlueprintDiscoveryService discovery;

    public BlueprintReceiptListener(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingConfigService craftingConfig,
            BlueprintDiscoveryService discovery
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!CustomItemUseSupport.isRightClick(event)) {
            return;
        }
        tryStudy(event.getPlayer(), CustomItemUseSupport.itemInHand(event), event.getHand(), () -> {
            CustomItemUseSupport.suppressVanilla(event);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        tryStudy(event.getPlayer(), CustomItemUseSupport.itemInHand(event), event.getHand(), () -> {
            event.setCancelled(true);
        });
    }

    private void tryStudy(Player player, ItemStack stack, EquipmentSlot hand, Runnable suppress) {
        CustomItemService service = core.customItemService();
        if (service == null || !isBlueprintReceipt(service, stack)) {
            return;
        }
        suppress.run();
        if (!canStudy(player)) {
            send(player, "<red>Study blueprints in the hub or after extracting from a raid.");
            return;
        }
        String blueprintId = service.resolve(stack)
                .map(instance -> BlueprintReceiptPayload.decode(instance.payloadCopy()).blueprintId())
                .orElse("");
        if (blueprintId.isBlank()) {
            send(player, "<red>This blueprint receipt is unreadable.");
            return;
        }
        Optional<BlueprintDefinition> blueprint = craftingConfig.blueprints().stream()
                .filter(entry -> entry.id().equals(blueprintId))
                .findFirst();
        if (blueprint.isEmpty()) {
            send(player, "<red>Unknown blueprint recipe.");
            return;
        }
        if (discovery.isDiscovered(player.getUniqueId(), blueprintId)) {
            // Keep the receipt — an already-known blueprint can be traded or sold instead.
            send(player, "<yellow>You already know <white>" + blueprint.get().displayName() + "<yellow>.");
            return;
        }
        discovery.discover(player.getUniqueId(), blueprintId);
        consumeOne(player, hand);
        send(player, "<green>Blueprint learned: <white>" + blueprint.get().displayName()
                + "<green>. Craft it at the armory workbench.");
    }

    private boolean canStudy(Player player) {
        if (engine.isSpectating(player)) {
            return false;
        }
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty() || BreachLobbyProtection.isLobbySafe(engine, player)) {
            return true;
        }
        BreachInstance breach = instance.get();
        return breach.hasExtracted(player.getUniqueId())
                || breach.isEliminated(player.getUniqueId())
                || breach.state() != BreachState.ACTIVE;
    }

    private static boolean isBlueprintReceipt(CustomItemService service, ItemStack stack) {
        return service != null
                && stack != null
                && !stack.getType().isAir()
                && service.isCustomItem(stack)
                && service.resolve(stack)
                .map(instance -> BlueprintReceiptDefinition.TYPE_ID.equals(instance.typeId()))
                .orElse(false);
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
