package network.skypvp.paper.item;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemBehavior;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemEquipEvent;
import network.skypvp.paper.item.api.CustomItemInstance;
import network.skypvp.paper.item.api.CustomItemProvider;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.item.api.CustomItemTypeId;
import network.skypvp.paper.item.api.CustomItemUnequipEvent;
import network.skypvp.paper.item.api.CustomStatEffect;
import network.skypvp.paper.item.api.EquipmentSlotGroup;
import network.skypvp.paper.item.api.LiveItemContext;
import network.skypvp.paper.item.api.StatContributor;
import network.skypvp.paper.library.ItemsLibrary;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public final class CustomItemServiceImpl implements CustomItemService {

    private final PaperCorePlugin plugin;
    private final CustomItemKeys keys;
    private final CustomItemCodec codec;
    private final CustomItemRegistry registry;
    private final LoreComposer loreComposer;
    private final ActiveEffectManager activeEffects;
    /** Stack modernizers (mode plugins register these); see {@link #reconcile(ItemStack)}. */
    private final List<UnaryOperator<ItemStack>> reconcilers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CustomItemServiceImpl(PaperCorePlugin plugin) {
        this.plugin = plugin;
        this.keys = new CustomItemKeys(plugin);
        this.codec = new CustomItemCodec(keys);
        this.registry = new CustomItemRegistry();
        this.loreComposer = new LoreComposer(registry);
        this.activeEffects = new ActiveEffectManager();
    }

    @Override
    public void registerProvider(CustomItemProvider provider) {
        registry.registerProvider(provider);
    }

    @Override
    public void registerReconciler(UnaryOperator<ItemStack> reconciler) {
        if (reconciler != null) {
            reconcilers.add(reconciler);
        }
    }

    @Override
    public ItemStack reconcile(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || reconcilers.isEmpty() || !isCustomItem(stack)) {
            return stack;
        }
        ItemStack current = stack;
        for (UnaryOperator<ItemStack> reconciler : reconcilers) {
            ItemStack updated = reconciler.apply(current);
            if (updated != null) {
                current = updated;
            }
        }
        return current;
    }

    public void syncProvidersFromServices() {
        for (RegisteredServiceProvider<CustomItemProvider> registration
                : plugin.getServer().getServicesManager().getRegistrations(CustomItemProvider.class)) {
            CustomItemProvider provider = registration.getProvider();
            if (provider != null) {
                registerProvider(provider);
            }
        }
    }

    @Override
    public Optional<CustomItemDefinition> definition(CustomItemTypeId typeId) {
        return registry.resolve(typeId).map(CustomItemRegistry.RegisteredType::definition);
    }

    @Override
    public Optional<CustomItemInstance> resolve(ItemStack stack) {
        return codec.read(stack);
    }

    @Override
    public boolean isCustomItem(ItemStack stack) {
        return codec.isCustomItem(stack);
    }

    @Override
    public ItemStack create(CustomItemTypeId typeId, Consumer<InstanceBuilder> mutator) {
        CustomItemRegistry.RegisteredType registered = registry.resolve(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown custom item type: " + typeId));
        MutableInstanceBuilder builder = new MutableInstanceBuilder(registered.definition().schemaVersion());
        if (mutator != null) {
            mutator.accept(builder);
        }
        int schemaVersion = builder.schemaVersion != null ? builder.schemaVersion : registered.definition().schemaVersion();
        byte[] payload = builder.payload != null ? builder.payload : new byte[0];
        UUID instanceId = CustomItemIdentity.createInstanceId(
                registered.definition().stackable(),
                typeId,
                payload,
                builder.instanceId
        );
        CustomItemInstance instance = new CustomItemInstance(instanceId, typeId, schemaVersion, payload);
        ItemStack stack = new ItemStack(registered.definition().displayMaterial());
        registered.definition().itemModel()
                .ifPresent(model -> stack.editMeta(meta -> meta.setItemModel(model)));
        codec.write(stack, instance);
        return refreshPresentation(stack, null);
    }

    @Override
    public ItemStack refreshPresentation(ItemStack stack, Player viewer) {
        Optional<CustomItemInstance> instanceOpt = codec.read(stack);
        if (instanceOpt.isEmpty()) {
            return stack;
        }
        CustomItemInstance instance = instanceOpt.get();
        Optional<CustomItemRegistry.RegisteredType> registeredOpt = registry.resolve(instance.typeId());
        if (registeredOpt.isEmpty()) {
            return stack;
        }
        CustomItemRegistry.RegisteredType registered = registeredOpt.get();
        if (registered.definition().stackable()) {
            UUID expected = CustomItemIdentity.stackableInstanceId(instance.typeId(), instance.payloadCopy());
            if (!expected.equals(instance.instanceId())) {
                instance = new CustomItemInstance(
                        expected,
                        instance.typeId(),
                        instance.schemaVersion(),
                        instance.payloadCopy()
                );
                codec.write(stack, instance);
            }
        }
        LiveItemContext ctx = new LiveItemContext(viewer, null, stack, instance, registered.definition());
        List<Component> lore = loreComposer.compose(ctx, viewer);
        ItemsLibrary.Builder builder = ItemsLibrary.builder(stack)
                .hideAttributes()
                .hideEnchants()
                .applyMeta(meta -> meta.addItemFlags(
                        ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                        ItemFlag.HIDE_UNBREAKABLE,
                        ItemFlag.HIDE_DESTROYS,
                        ItemFlag.HIDE_PLACED_ON
                ));
        loreComposer.displayName(ctx, viewer).ifPresent(name -> builder.applyMeta(meta -> meta.displayName(name)));
        if (!lore.isEmpty()) {
            builder.applyMeta(meta -> meta.lore(lore));
        }
        registry.behavior(instance.typeId()).ifPresent(behavior -> behavior.onRefresh(ctx));
        return builder.build();
    }

    @Override
    public ItemStack updatePayload(ItemStack stack, UnaryOperator<byte[]> payloadTransform) {
        Optional<CustomItemInstance> instanceOpt = codec.read(stack);
        if (instanceOpt.isEmpty()) {
            return stack;
        }
        CustomItemInstance current = instanceOpt.get();
        byte[] payload = payloadTransform != null
                ? payloadTransform.apply(current.payloadCopy())
                : current.payloadCopy();
        if (payload == null) {
            payload = new byte[0];
        }
        CustomItemInstance updated = new CustomItemInstance(
                resolveInstanceId(current.typeId(), payload, current.instanceId()),
                current.typeId(),
                current.schemaVersion(),
                payload
        );
        ItemStack copy = stack.clone();
        codec.write(copy, updated);
        return refreshPresentation(copy, null);
    }

    @Override
    public double namedStat(Player player, String key) {
        return activeEffects.namedStat(player, key);
    }

    public void handleEquipmentChange(Player player, EquipmentSlotGroup slot, ItemStack previous, ItemStack current) {
        unequipIfNeeded(player, slot, previous);
        equipIfNeeded(player, slot, current);
    }

    @Override
    public void scanPlayerEquipment(Player player) {
        for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
            ItemStack stack = player.getInventory().getItem(slot.bukkitSlot());
            if (stack == null || stack.getType().isAir()) {
                activeEffects.clear(player, slot);
                continue;
            }
            Optional<CustomItemInstance> instanceOpt = codec.read(stack);
            if (instanceOpt.isEmpty()) {
                activeEffects.clear(player, slot);
                continue;
            }
            Optional<CustomItemRegistry.RegisteredType> registeredOpt = registry.resolve(instanceOpt.get().typeId());
            if (registeredOpt.isEmpty()
                    || !CustomItemEquipRules.acceptsSlot(registeredOpt.get().definition(), slot)) {
                activeEffects.clear(player, slot);
                continue;
            }
            equipIfNeeded(player, slot, stack);
        }
    }

    public void clearPlayer(Player player) {
        for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
            activeEffects.clear(player, slot);
        }
        activeEffects.clearAll(player);
    }

    private void unequipIfNeeded(Player player, EquipmentSlotGroup slot, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        Optional<CustomItemInstance> instanceOpt = codec.read(stack);
        if (instanceOpt.isEmpty()) {
            return;
        }
        CustomItemInstance instance = instanceOpt.get();
        Optional<CustomItemRegistry.RegisteredType> registeredOpt = registry.resolve(instance.typeId());
        if (registeredOpt.isEmpty()) {
            return;
        }
        CustomItemRegistry.RegisteredType registered = registeredOpt.get();
        if (!CustomItemEquipRules.acceptsSlot(registered.definition(), slot)) {
            activeEffects.clear(player, slot);
            return;
        }
        LiveItemContext ctx = new LiveItemContext(player, slot, stack, instance, registered.definition());
        CustomItemUnequipEvent event = new CustomItemUnequipEvent(player, slot, instance, registered.definition(), stack);
        Bukkit.getPluginManager().callEvent(event);
        activeEffects.clear(player, slot);
        registry.behavior(instance.typeId()).ifPresent(behavior -> behavior.onUnequip(ctx));
    }

    private void equipIfNeeded(Player player, EquipmentSlotGroup slot, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        Optional<CustomItemInstance> instanceOpt = codec.read(stack);
        if (instanceOpt.isEmpty()) {
            return;
        }
        CustomItemInstance instance = instanceOpt.get();
        Optional<CustomItemRegistry.RegisteredType> registeredOpt = registry.resolve(instance.typeId());
        if (registeredOpt.isEmpty()) {
            plugin.getLogger().warning("[CustomItems] Unknown equipped item type " + instance.typeId() + " on " + player.getName());
            return;
        }
        CustomItemRegistry.RegisteredType registered = registeredOpt.get();
        if (!CustomItemEquipRules.acceptsSlot(registered.definition(), slot)) {
            activeEffects.clear(player, slot);
            return;
        }
        LiveItemContext ctx = new LiveItemContext(player, slot, stack, instance, registered.definition());
        CustomItemEquipEvent event = new CustomItemEquipEvent(player, slot, instance, registered.definition(), stack);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        List<CustomStatEffect> effects = new ArrayList<>();
        Optional<StatContributor> statContributor = registry.statContributor(instance.typeId());
        if (statContributor.isPresent()) {
            effects.addAll(statContributor.get().effects(ctx));
        }
        activeEffects.apply(player, slot, effects);
        registry.behavior(instance.typeId()).ifPresent(behavior -> behavior.onEquip(ctx));
        refreshPresentation(stack, player);
    }

    private UUID resolveInstanceId(CustomItemTypeId typeId, byte[] payload, UUID currentInstanceId) {
        return registry.resolve(typeId)
                .filter(registered -> registered.definition().stackable())
                .map(ignored -> CustomItemIdentity.stackableInstanceId(typeId, payload))
                .orElse(currentInstanceId);
    }

    private static final class MutableInstanceBuilder implements InstanceBuilder {
        private UUID instanceId;
        private byte[] payload;
        private Integer schemaVersion;

        private MutableInstanceBuilder(int defaultSchemaVersion) {
            this.schemaVersion = defaultSchemaVersion;
        }

        @Override
        public void instanceId(UUID id) {
            this.instanceId = id;
        }

        @Override
        public void payload(byte[] payload) {
            this.payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public void schemaVersion(int version) {
            this.schemaVersion = version;
        }
    }
}
