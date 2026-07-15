package network.skypvp.extraction.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Validates armor module piece slots and mutual exclusion rules. */
public final class ModuleSlotRules {

    private ModuleSlotRules() {
    }

    public static boolean canInstallOnPiece(ArmorModuleType type, InfuseArmorPiece piece) {
        if (type == null || piece == null) {
            return false;
        }
        return type.compatiblePieces().contains(piece);
    }

    public static String conflictReason(InfuseArmorPayload armor, InfuseArmorPiece piece, ArmorModuleType incoming) {
        if (incoming == null) {
            return "Unknown module.";
        }
        if (!canInstallOnPiece(incoming, piece)) {
            return incoming.displayName() + " cannot be installed on " + piece.label().toLowerCase(Locale.ROOT) + ".";
        }
        Set<String> installed = installedModuleIds(armor, piece);
        for (String installedId : installed) {
            if (incoming.conflictsWith().contains(installedId)) {
                ArmorModuleType existing = ArmorModuleType.byId(installedId).orElse(null);
                String name = existing == null ? installedId : existing.displayName();
                return incoming.displayName() + " conflicts with installed " + name + ".";
            }
            ArmorModuleType existingType = ArmorModuleType.byId(installedId).orElse(null);
            if (existingType != null && existingType.conflictsWith().contains(incoming.id())) {
                return incoming.displayName() + " conflicts with installed " + existingType.displayName() + ".";
            }
        }
        return null;
    }

    public static Set<String> installedModuleIds(InfuseArmorPayload payload, InfuseArmorPiece piece) {
        Set<String> ids = new LinkedHashSet<>();
        if (payload == null || piece == null) {
            return ids;
        }
        for (String moduleId : payload.moduleSockets()) {
            if (moduleId != null && !moduleId.isBlank()) {
                ids.add(moduleId);
            }
        }
        if (piece.isChestplate() && payload.overclockModule() != null && !payload.overclockModule().isBlank()) {
            ids.add(payload.overclockModule());
        }
        return ids;
    }

    public static boolean hasAttachments(InfuseArmorPayload payload, InfuseArmorPiece piece) {
        return !installedModuleIds(payload, piece).isEmpty()
                || (piece.isChestplate() && payload.shieldModule() != null && !payload.shieldModule().isBlank());
    }

    public static List<String> installedAcrossLoadout(CustomItemService service, Player player) {
        List<String> ids = new ArrayList<>();
        if (service == null || player == null) {
            return ids;
        }
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack == null || stack.getType().isAir() || !service.isCustomItem(stack)) {
                continue;
            }
            service.resolve(stack).ifPresent(instance -> {
                InfuseArmorPiece piece = InfuseArmorPiece.byTypeId(instance.typeId()).orElse(null);
                if (piece == null) {
                    return;
                }
                InfuseArmorPayload payload = InfuseArmorPayload.decode(instance.payloadCopy());
                ids.addAll(installedModuleIds(payload, piece));
            });
        }
        return ids;
    }
}
