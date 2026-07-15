package network.skypvp.extraction.gameplay;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseLayout;
import network.skypvp.extraction.gameplay.corpse.BreachPlayerCorpseService;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import network.skypvp.extraction.integration.LibsDisguisesBridge;
import network.skypvp.paper.library.npc.RealPlayerNpc;

/**
 * Equips Ruins gunner MythicMobs with Infuse armor and drops extraction custom loot into a lootable corpse
 * (never WM weapons, never world item drops).
 */
public final class BreachRuinsMobService implements Listener {

    public static final String RIFLEMAN_ID = "RuinsRifleman";
    public static final String SMGUNNER_ID = "RuinsSMGunner";
    public static final String BREACHER_ID = "RuinsBreacher";
    public static final String PISTOL_GUNNER_ID = "RuinsPistolGunner";
    public static final String KNIFE_RUSHER_ID = "RuinsKnifeRusher";

    /** Unlooted AI corpses despawn after 90s so raids do not accumulate bodies. */
    private static final long MOB_CORPSE_LIFETIME_TICKS = 20L * 90L;

    private final PaperCorePlugin core;
    private final BreachEngine breachEngine;
    private final CraftingConfigService craftingConfig;
    private final Logger logger;
    private BreachRuinsRaiderAiService raiderAiService;
    private BreachRuinsMobNametagService nametagService;
    private BreachPlayerCorpseService corpseService;

    public BreachRuinsMobService(
            PaperCorePlugin core,
            BreachEngine breachEngine,
            CraftingConfigService craftingConfig,
            Logger logger
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.breachEngine = Objects.requireNonNull(breachEngine, "breachEngine");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void bindRaiderAiService(BreachRuinsRaiderAiService raiderAiService) {
        this.raiderAiService = raiderAiService;
    }

    public void bindNametagService(BreachRuinsMobNametagService nametagService) {
        this.nametagService = nametagService;
    }

    public void bindCorpseService(BreachPlayerCorpseService corpseService) {
        this.corpseService = corpseService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMythicMobSpawn(MythicMobSpawnEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            String mobType = event.getMobType().getInternalName();
            if (!isRuinsGunner(mobType)) {
                return;
            }
            if (!isInActiveBreach(living)) {
                return;
            }
            ArmorSet armorSet = switch (mobType) {
                case SMGUNNER_ID -> ArmorSet.STRIKER;
                case BREACHER_ID -> ArmorSet.VANGUARD;
                case PISTOL_GUNNER_ID -> ArmorSet.STRIKER;
                default -> ArmorSet.VANGUARD;
            };
            RealPlayerNpc.applySurvivalTraits(living);
            equipInfuseSet(living, GearRarity.UNCOMMON, armorSet);
            if (raiderAiService != null) {
                int level = 1;
                try {
                    Optional<ActiveMob> active = MythicBukkit.inst().getMobManager().getActiveMob(living.getUniqueId());
                    if (active.isPresent()) {
                        level = Math.max(1, (int) Math.round(active.get().getLevel()));
                    }
                } catch (RuntimeException ignored) {
                }
                raiderAiService.track(living, mobType, level);
            }
            tagEntityMobType(living, mobType);
            applyPlayerDisguise(living, mobType);
            attachNametag(living, mobType);
        }
    }

    private void attachNametag(LivingEntity living, String mobType) {
        if (nametagService == null) {
            return;
        }
        Runnable attach = () -> {
            if (!living.isValid() || living.isDead()) {
                return;
            }
            living.setCustomNameVisible(false);
            nametagService.attachIfAbsent(living, mobType, plainName(living.getCustomName()));
        };
        if (core.platform() != null) {
            core.platform().runAtEntity(living, attach);
        } else {
            attach.run();
        }
    }

    private void applyPlayerDisguise(LivingEntity living, String mobType) {
        String displayName = plainName(living.getCustomName());
        String skin = LibsDisguisesBridge.skinForMobType(mobType);
        Runnable apply = () -> {
            if (!living.isValid() || living.isDead()) {
                return;
            }
            LibsDisguisesBridge.applyPlayerDisguise(living, displayName, skin);
        };
        if (core.platform() != null) {
            core.platform().runAtEntity(living, apply);
            for (long delayTicks : new long[] {5L, 20L, 40L}) {
                core.platform().runGlobalLater(() -> core.platform().runAtEntity(living, apply), delayTicks);
            }
        } else {
            apply.run();
        }
    }

    private static String plainName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Raider";
        }
        return raw.replaceAll("(?i)§[0-9a-fk-or]", "").trim();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (nametagService == null || !isInActiveBreach(living)) {
            return;
        }
        Optional<String> mobType = resolveMobType(living);
        if (mobType.isEmpty() || !isRuinsGunner(mobType.get())) {
            return;
        }
        Runnable refresh = () -> nametagService.refresh(living);
        if (core.platform() != null) {
            core.platform().runAtEntity(living, refresh);
            core.platform().runGlobalLater(() -> core.platform().runAtEntity(living, refresh), 1L);
        } else {
            refresh.run();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityCombust(EntityCombustEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!isInActiveBreach(living)) {
            return;
        }
        Optional<String> mobType = resolveMobType(living);
        if (mobType.isEmpty() || !isRuinsGunner(mobType.get())) {
            return;
        }
        event.setCancelled(true);
        RealPlayerNpc.cancelCombust(living);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isInActiveBreach(entity)) {
            return;
        }
        Optional<String> mobType = resolveMobType(entity);
        if (mobType.isEmpty() || !isRuinsGunner(mobType.get())) {
            return;
        }
        if (nametagService != null) {
            nametagService.detach(entity);
        }
        event.getDrops().clear();
        event.setDroppedExp(0);

        Player gunnerKiller = entity.getKiller();
        if (gunnerKiller != null && core.playerLevelService() != null) {
            core.playerLevelService().addXp(gunnerKiller, 12L, "raider_kill");
        }

        List<ItemStack> rolled = new ArrayList<>();
        ExtractionLootFactory lootFactory = new ExtractionLootFactory(core, craftingConfig);
        for (MobDrop drop : dropsFor(mobType.get())) {
            if (ThreadLocalRandom.current().nextDouble() > drop.chance()) {
                continue;
            }
            lootFactory.customItem(drop.itemSpec(), drop.amount()).ifPresent(rolled::add);
        }
        if (rolled.isEmpty() || corpseService == null) {
            return;
        }
        ItemStack[] loot = new ItemStack[BreachPlayerCorpseLayout.INVENTORY_SIZE];
        for (int i = 0; i < rolled.size() && i < loot.length; i++) {
            loot[i] = rolled.get(i);
        }
        String displayName = switch (mobType.get().toLowerCase(Locale.ROOT)) {
            case "ruinssmgunner" -> "Ruins SMGunner";
            case "ruinsbreacher" -> "Ruins Breacher";
            case "ruinspistolgunner" -> "Ruins Pistol Gunner";
            case "ruinskniferusher" -> "Ruins Knife Rusher";
            default -> "Ruins Rifleman";
        };
        String skin = LibsDisguisesBridge.skinForMobType(mobType.get());
        corpseService.spawnMobCorpse(
                entity.getLocation().clone(),
                loot,
                displayName,
                skin,
                MOB_CORPSE_LIFETIME_TICKS
        );
    }

    private static boolean isRuinsGunner(String mobType) {
        if (mobType == null) {
            return false;
        }
        return RIFLEMAN_ID.equalsIgnoreCase(mobType)
                || SMGUNNER_ID.equalsIgnoreCase(mobType)
                || BREACHER_ID.equalsIgnoreCase(mobType)
                || PISTOL_GUNNER_ID.equalsIgnoreCase(mobType)
                || KNIFE_RUSHER_ID.equalsIgnoreCase(mobType);
    }

    /** Returns true when the entity is an active Ruins gunner MythicMob. */
    public static boolean isRuinsGunnerEntity(Entity entity) {
        return resolveMobType(entity).map(BreachRuinsMobService::isRuinsGunner).orElse(false);
    }

    public static boolean isRuinsGunnerType(String mobType) {
        return isRuinsGunner(mobType);
    }

    public static Optional<String> resolveMobType(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        if (RaiderGunnerKeys.mobTypeKey() != null) {
            String tagged = entity.getPersistentDataContainer().get(
                    RaiderGunnerKeys.mobTypeKey(),
                    org.bukkit.persistence.PersistentDataType.STRING
            );
            if (tagged != null && !tagged.isBlank() && isRuinsGunner(tagged)) {
                return Optional.of(tagged);
            }
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            return Optional.empty();
        }
        try {
            Optional<ActiveMob> activeMob = MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId());
            Optional<String> mythicType = activeMob.map(ActiveMob::getMobType);
            mythicType.ifPresent(type -> tagEntityMobType(entity, type));
            return mythicType;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static void tagEntityMobType(Entity entity, String mobType) {
        if (entity == null || mobType == null || RaiderGunnerKeys.mobTypeKey() == null) {
            return;
        }
        entity.getPersistentDataContainer().set(
                RaiderGunnerKeys.mobTypeKey(),
                org.bukkit.persistence.PersistentDataType.STRING,
                mobType.toLowerCase(Locale.ROOT)
        );
    }

    private boolean isInActiveBreach(Entity entity) {
        if (entity == null || entity.getWorld() == null) {
            return false;
        }
        Player probe = entity instanceof Player player ? player : null;
        if (probe != null) {
            return breachEngine.instanceFor(probe).isPresent();
        }
        return breachEngine.instanceForWorld(entity.getWorld()).isPresent();
    }


    private void equipInfuseSet(LivingEntity entity, GearRarity rarity, ArmorSet armorSet) {
        CustomItemService service = core.customItemService();
        if (service == null) {
            return;
        }
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setHelmet(copy(ExtractionCustomItemProvider.createInfuseArmor(service, InfuseArmorPiece.HELMET, rarity, armorSet)));
        equipment.setChestplate(copy(ExtractionCustomItemProvider.createInfuseArmor(service, InfuseArmorPiece.CHESTPLATE, rarity, armorSet)));
        equipment.setLeggings(copy(ExtractionCustomItemProvider.createInfuseArmor(service, InfuseArmorPiece.LEGGINGS, rarity, armorSet)));
        equipment.setBoots(copy(ExtractionCustomItemProvider.createInfuseArmor(service, InfuseArmorPiece.BOOTS, rarity, armorSet)));
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
        equipment.setItemInMainHandDropChance(0.0F);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.clone();
    }

    private static MobDrop[] dropsFor(String mobType) {
        return switch (mobType.toLowerCase(Locale.ROOT)) {
            case "ruinsrifleman" -> new MobDrop[] {
                    new MobDrop("material:metal_shards", 2, 0.85),
                    new MobDrop("material:alloy_plate", 1, 0.35),
                    new MobDrop("material:polymer_sheet", 1, 0.45),
                    new MobDrop("medic:sterile_bandage", 1, 0.30),
                    new MobDrop("blueprint:module_sprinter", 1, 0.08)
            };
            case "ruinssmgunner" -> new MobDrop[] {
                    new MobDrop("material:cloth_scrap", 3, 0.90),
                    new MobDrop("material:fiber_bundle", 2, 0.70),
                    new MobDrop("material:polymer_sheet", 1, 0.40),
                    new MobDrop("medic:bandage_rag", 2, 0.55),
                    new MobDrop("blueprint:medic_medkit", 1, 0.06)
            };
            case "ruinsbreacher" -> new MobDrop[] {
                    new MobDrop("material:metal_shards", 3, 0.85),
                    new MobDrop("material:alloy_plate", 2, 0.55),
                    new MobDrop("material:field_suture", 2, 0.50),
                    new MobDrop("medic:sterile_bandage", 1, 0.45),
                    new MobDrop("blueprint:module_tread", 1, 0.07)
            };
            case "ruinspistolgunner" -> new MobDrop[] {
                    new MobDrop("material:cloth_scrap", 2, 0.80),
                    new MobDrop("material:polymer_sheet", 1, 0.35),
                    new MobDrop("medic:bandage_rag", 1, 0.40),
                    new MobDrop("blueprint:module_sprinter", 1, 0.05)
            };
            case "ruinskniferusher" -> new MobDrop[] {
                    new MobDrop("material:cloth_scrap", 2, 0.85),
                    new MobDrop("material:fiber_bundle", 1, 0.55),
                    new MobDrop("medic:bandage_rag", 1, 0.45),
                    new MobDrop("blueprint:module_sprinter", 1, 0.06)
            };
            default -> new MobDrop[0];
        };
    }

    private record MobDrop(String itemSpec, int amount, double chance) {
    }
}
