package network.skypvp.extraction.listener;

import java.util.Objects;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.RechargerTier;
import network.skypvp.extraction.item.ShieldRechargeService;
import network.skypvp.extraction.item.ShieldRechargerPayload;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Recharges the socketed Infuse shield when the player finishes drinking a Shield Recharger potion. Using a drinkable
 * potion gives the recharge a natural cast delay (the vanilla drink animation) before it applies. The recharger's tier
 * then sets the fill pace: gradual for low tiers, instant for the top tier. Destroyed shields cannot be recharged
 * (require armory repair — future feature).
 */
public final class ShieldRechargerListener implements Listener {

    private final PaperCorePlugin core;
    private final ShieldRechargeService rechargeService;

    public ShieldRechargerListener(PaperCorePlugin core, ShieldRechargeService rechargeService) {
        this.core = Objects.requireNonNull(core, "core");
        this.rechargeService = Objects.requireNonNull(rechargeService, "rechargeService");
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        CustomItemService service = core.customItemService();
        if (service == null) {
            return;
        }
        ItemStack consumed = event.getItem();
        if (!InfuseArmorMutator.isShieldRecharger(service, consumed)) {
            return;
        }
        // Take over consumption so vanilla doesn't hand back a glass bottle or drink it on failure.
        event.setCancelled(true);

        Player player = event.getPlayer();
        RechargerTier tier = service.resolve(consumed)
                .map(instance -> ShieldRechargerPayload.decode(instance.payloadCopy()).tier())
                .orElse(RechargerTier.FIELD);

        ShieldRechargeService.Outcome outcome = rechargeService.beginRecharge(player, tier);
        send(player, rechargeService.describe(outcome, tier));

        if (outcome == ShieldRechargeService.Outcome.STARTED
                || outcome == ShieldRechargeService.Outcome.INSTANT) {
            consumeOne(player, event.getHand());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rechargeService.cancel(event.getPlayer().getUniqueId());
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
