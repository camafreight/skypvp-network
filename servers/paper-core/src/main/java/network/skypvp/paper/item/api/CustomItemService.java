package network.skypvp.paper.item.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Public facade for the custom item engine. Registered on {@link org.bukkit.plugin.ServicesManager}.
 */
public interface CustomItemService {

    void registerProvider(CustomItemProvider provider);

    Optional<CustomItemDefinition> definition(CustomItemTypeId typeId);

    Optional<CustomItemInstance> resolve(ItemStack stack);

    boolean isCustomItem(ItemStack stack);

    ItemStack create(CustomItemTypeId typeId, Consumer<InstanceBuilder> mutator);

    ItemStack refreshPresentation(ItemStack stack, Player viewer);

    /**
     * Registers a stack modernizer. Reconcilers receive persisted custom item stacks whose
     * presentation (display material, item model, name/lore) may predate the current build
     * and return an updated stack — or {@code null} when the stack is already current.
     * Implementations must preserve payload, instance identity, and amount.
     */
    default void registerReconciler(UnaryOperator<ItemStack> reconciler) {
    }

    /**
     * Runs all registered reconcilers over {@code stack}. Storage decode paths (vault,
     * material stash) and join-time inventory sweeps call this, so stacks minted before an
     * item's material/model changed self-update to the new look.
     */
    default ItemStack reconcile(ItemStack stack) {
        return stack;
    }

    ItemStack updatePayload(ItemStack stack, UnaryOperator<byte[]> payloadTransform);

    /** Re-scans all equipment slots after a payload mutation on a worn item. */
    void scanPlayerEquipment(Player player);

    double namedStat(Player player, String key);

    interface InstanceBuilder {
        void instanceId(UUID id);

        void payload(byte[] payload);

        void schemaVersion(int version);
    }
}
