package network.skypvp.extraction.integration;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import network.skypvp.paper.library.packet.PacketEventsBridge;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spoofs main-hand equipment to a charged crossbow for <em>other</em> viewers so clients use
 * {@code CROSSBOW_HOLD}, without changing the real FEATHER gun item (inventory stays WM-safe).
 */
public final class WeaponCrossbowPosePacketService {

    private final WeaponMechanicsBridge weapons;
    private PacketListenerAbstract packetListener;

    private WeaponCrossbowPosePacketService(WeaponMechanicsBridge weapons) {
        this.weapons = weapons;
    }

    public static WeaponCrossbowPosePacketService register(JavaPlugin plugin, WeaponMechanicsBridge weapons) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(weapons, "weapons");
        if (!weapons.isAvailable() || !PacketEventsBridge.isAvailable()) {
            plugin.getLogger().info("[Breach] Crossbow pose packets skipped (WM or PacketEvents missing).");
            return null;
        }
        WeaponCrossbowPosePacketService service = new WeaponCrossbowPosePacketService(weapons);
        service.packetListener = new PacketListenerAbstract(PacketListenerPriority.HIGH) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                service.onEquipmentSend(event);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(service.packetListener);
        plugin.getLogger().info("[Breach] Firearm hold pose via equipment packet spoof (charged crossbow to viewers).");
        return service;
    }

    public void shutdown() {
        if (packetListener != null && PacketEventsBridge.isAvailable()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            } catch (RuntimeException ignored) {
            }
            packetListener = null;
        }
    }

    private void onEquipmentSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_EQUIPMENT) {
            return;
        }
        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player viewer) || !viewer.isOnline()) {
            return;
        }
        WrapperPlayServerEntityEquipment wrapper;
        try {
            wrapper = new WrapperPlayServerEntityEquipment(event);
        } catch (RuntimeException ex) {
            return;
        }
        // Holder sees their real inventory / FP models — never rewrite self equipment.
        if (wrapper.getEntityId() == viewer.getEntityId()) {
            return;
        }

        List<Equipment> equipment = wrapper.getEquipment();
        if (equipment == null || equipment.isEmpty()) {
            return;
        }
        List<Equipment> rewritten = null;
        for (int i = 0; i < equipment.size(); i++) {
            Equipment slot = equipment.get(i);
            if (slot == null || slot.getSlot() != EquipmentSlot.MAIN_HAND) {
                continue;
            }
            ItemStack real;
            try {
                real = SpigotConversionUtil.toBukkitItemStack(slot.getItem());
            } catch (RuntimeException ex) {
                continue;
            }
            if (real == null || real.getType().isAir()) {
                continue;
            }
            // Always clone before any mutation — packet ItemStacks can alias inventory slots.
            ItemStack cosmetic = WeaponShootingPose.cosmeticChargedCrossbow(real.clone(), weapons);
            if (cosmetic == null) {
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(equipment);
            }
            rewritten.set(i, new Equipment(EquipmentSlot.MAIN_HAND, SpigotConversionUtil.fromBukkitItemStack(cosmetic)));
        }
        if (rewritten == null) {
            return;
        }
        wrapper.setEquipment(rewritten);
        event.markForReEncode(true);
    }
}
