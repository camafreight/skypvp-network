package network.skypvp.paper.item;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class CustomItemEquipmentListener implements Listener {

    private final CustomItemServiceImpl service;

    public CustomItemEquipmentListener(CustomItemServiceImpl service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        service.scanPlayerEquipment(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        service.clearPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        EquipmentSlotGroup slot = EquipmentSlotGroup.fromBukkit(event.getSlot());
        if (slot == null) {
            return;
        }
        service.handleEquipmentChange(event.getPlayer(), slot, event.getOldItem(), event.getNewItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack current = player.getInventory().getItemInMainHand();
        service.handleEquipmentChange(player, EquipmentSlotGroup.MAIN_HAND, previous, current);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        service.handleEquipmentChange(
                player,
                EquipmentSlotGroup.MAIN_HAND,
                event.getMainHandItem(),
                player.getInventory().getItemInMainHand()
        );
        service.handleEquipmentChange(
                player,
                EquipmentSlotGroup.OFF_HAND,
                event.getOffHandItem(),
                player.getInventory().getItemInOffHand()
        );
    }
}
