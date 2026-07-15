package network.skypvp.extraction.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialDefinition;
import network.skypvp.extraction.crafting.CraftingMaterialItemFactory;
import network.skypvp.extraction.item.ArmorMark;
import network.skypvp.extraction.item.ArmorModuleType;
import network.skypvp.extraction.item.ArmorSet;
import network.skypvp.extraction.item.ExtractionCombatDefense;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.extraction.item.GearRarity;
import network.skypvp.extraction.item.InfuseArmorMutator;
import network.skypvp.extraction.item.InfuseArmorPayload;
import network.skypvp.extraction.item.InfuseArmorPiece;
import network.skypvp.extraction.item.InfuseChestplateDefinition;
import network.skypvp.extraction.item.RechargerTier;
import network.skypvp.extraction.item.ShieldModuleDefinition;
import network.skypvp.extraction.item.ShieldSlotRules;
import network.skypvp.extraction.item.ShieldSocketReference;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.item.api.CustomItemDefinition;
import network.skypvp.paper.item.api.CustomItemProvider;
import network.skypvp.paper.item.api.CustomItemService;
import network.skypvp.paper.item.api.ItemCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class BreachItemsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> RARITIES = Arrays.stream(GearRarity.values())
            .map(r -> r.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final List<String> INFUSE_PIECES = Arrays.stream(InfuseArmorPiece.values())
            .map(piece -> piece.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final List<String> INFUSE_GIVE_TARGETS;
    private static final List<String> ARMOR_SETS = Arrays.stream(ArmorSet.values())
            .map(ArmorSet::id)
            .toList();
    private static final List<String> MARKS = Arrays.stream(ArmorMark.values())
            .map(mark -> mark.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final List<String> RECHARGER_TIERS = Arrays.stream(RechargerTier.values())
            .map(tier -> tier.name().toLowerCase(Locale.ROOT))
            .toList();
    private static final List<String> MODULE_IDS = Arrays.stream(ArmorModuleType.values())
            .map(ArmorModuleType::id)
            .toList();

    static {
        List<String> targets = new ArrayList<>(INFUSE_PIECES);
        targets.add("set");
        INFUSE_GIVE_TARGETS = List.copyOf(targets);
    }

    private final PaperCorePlugin core;
    private final CraftingConfigService craftingConfig;
    private final network.skypvp.extraction.backpack.BackpackService backpackService;

    public BreachItemsCommand(
            PaperCorePlugin core,
            ExtractionCustomItemProvider itemProvider,
            CraftingConfigService craftingConfig,
            network.skypvp.extraction.backpack.BackpackService backpackService
    ) {
        this.core = core;
        this.craftingConfig = craftingConfig;
        this.backpackService = backpackService;
    }

    private static void send(CommandSender sender, String miniMessage) {
        String locale = sender instanceof Player player
                ? ExtractionTexts.locale(player)
                : ExtractionTexts.defaultLocale();
        Component component = ExtractionTexts.miniMessageTemplate(miniMessage, locale);
        sender.sendMessage(component);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "<gray>Usage: /breachitems <giveinfuse|giveshield|giverecharger|givemodule|givematerials|giveblueprint|setmark|setshield|clearshield|breakshield|status> ...");
            send(sender, "<gray>  giveinfuse: [helmet|chestplate|leggings|boots|set] [rarity] [armor_set]");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "giveinfuse" -> handleGiveInfuse(sender, args);
            case "giveshield" -> handleGiveShield(sender, args);
            case "giverecharger" -> handleGiveRecharger(sender, args);
            case "givemodule" -> handleGiveModule(sender, args);
            case "givematerials" -> handleGiveMaterials(sender, args);
            case "giveblueprint" -> handleGiveBlueprint(sender, args);
            case "givebackpack" -> handleGiveBackpack(sender, args);
            case "setmark" -> handleSetMark(sender, args);
            case "setshield" -> handleSetShield(sender);
            case "clearshield" -> handleClearShield(sender);
            case "breakshield" -> handleBreakShield(sender);
            case "status" -> handleStatus(sender);
            default -> {
                send(sender, "<red>Unknown subcommand. Use giveinfuse, giveshield, giverecharger, givemodule, givematerials, giveblueprint, setmark, setshield, clearshield, breakshield, or status.");
                yield true;
            }
        };
    }

    private boolean handleGiveBackpack(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null || backpackService == null) {
            send(sender, "<red>Backpack service is unavailable.");
            return true;
        }
        int tier;
        try {
            tier = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        } catch (NumberFormatException ignored) {
            send(sender, "<red>Usage: /breachitems givebackpack <1-4>");
            return true;
        }
        if (tier < 1 || tier > 4) {
            send(sender, "<red>Backpack tiers run 1 (one row) to 4 (all rows incl. hotbar).");
            return true;
        }
        String skin = args.length >= 3 ? args[2] : network.skypvp.extraction.item.BackpackSkins.DEFAULT_ID;
        if (!network.skypvp.extraction.item.BackpackSkins.exists(skin)) {
            send(sender, "<red>Unknown skin. Options: " + network.skypvp.extraction.item.BackpackSkins.ALL.stream()
                    .map(network.skypvp.extraction.item.BackpackSkins.Skin::id)
                    .collect(java.util.stream.Collectors.joining(", ")));
            return true;
        }
        backpackService.give(player, tier, wanted ->
                ExtractionCustomItemProvider.createBackpack(service, wanted, skin));
        return true;
    }

    private boolean handleGiveRecharger(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        RechargerTier tier = args.length >= 2 ? RechargerTier.fromId(args[1]) : RechargerTier.FIELD;
        ItemStack recharger = ExtractionCustomItemProvider.createShieldRecharger(service, tier);
        player.getInventory().addItem(recharger).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
        send(sender, "<green>Gave a " + tier.displayName() + " (+" + tier.amountLabel() + " @ "
                + tier.rateLabel() + "). Drink to recharge your shield.");
        return true;
    }

    private boolean handleGiveModule(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "<gray>Usage: /breachitems givemodule <" + String.join("|", MODULE_IDS) + ">");
            return true;
        }
        ArmorModuleType type = ArmorModuleType.byId(args[1]).orElse(null);
        if (type == null) {
            send(sender, "<red>Unknown module. Use: " + String.join(", ", MODULE_IDS));
            return true;
        }
        ItemStack module = ExtractionCustomItemProvider.createArmorModule(service, type);
        if (module == null || module.getType().isAir()) {
            send(sender, "<red>Failed to create module.");
            return true;
        }
        player.getInventory().addItem(module).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
        send(sender, "<green>Gave " + type.displayName() + ". Install it via /infuse ("
                + (type.overclock() ? "overclock socket" : "module socket") + ").");
        return true;
    }

    private boolean handleGiveMaterials(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null || craftingConfig == null) {
            send(sender, "<red>Crafting services are unavailable.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "<gray>Usage: /breachitems givematerials <" + String.join("|", materialIds()) + "> [amount]");
            return true;
        }
        String materialId = args[1].trim().toLowerCase(Locale.ROOT);
        CraftingMaterialDefinition def = CraftingMaterialDefinition.byId(materialId, craftingConfig.materials()).orElse(null);
        if (def == null) {
            send(sender, "<red>Unknown material. Use: " + String.join(", ", materialIds()));
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                send(sender, "<red>Invalid amount.");
                return true;
            }
        }
        int given = 0;
        for (ItemStack stack : CraftingMaterialItemFactory.splitStacks(service, craftingConfig, materialId, amount)) {
            player.getInventory().addItem(stack).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            given += stack.getAmount();
        }
        if (given <= 0) {
            send(sender, "<red>Failed to create material items.");
            return true;
        }
        send(sender, "<green>Gave <white>" + given + "x " + def.displayName() + "<green>.");
        return true;
    }

    private boolean handleBreakShield(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            send(sender, "<red>Wear or hold an Infuse chestplate.");
            return true;
        }
        ShieldSlotRules.Result result = InfuseArmorMutator.destroyShield(service, player, armor);
        if (result instanceof ShieldSlotRules.Result.Failure failure) {
            send(sender, "<red>" + failure.message());
            return true;
        }
        send(sender, "<yellow>Shield destroyed (test). It cannot be recharged until repaired at the armory.");
        return true;
    }

    private boolean handleGiveInfuse(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }

        InfuseGiveRequest request = parseGiveInfuseRequest(sender, args);
        if (request == null) {
            return true;
        }

        if (request.fullSet()) {
            for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
                if (service.definition(piece.typeId()).isEmpty()) {
                    send(sender, "<red>Infuse " + piece.label().toLowerCase(Locale.ROOT)
                            + " is not registered. Is breach bootstrap complete?");
                    return true;
                }
            }
            try {
                for (InfuseArmorPiece piece : InfuseArmorPiece.values()) {
                    giveInfusePiece(player, service, piece, request.rarity(), request.armorSet());
                }
            } catch (RuntimeException ex) {
                send(sender, "<red>Failed to create Infuse set: " + ex.getMessage());
                return true;
            }
            send(sender, "<green>Gave full " + request.armorSet().displayName() + " ("
                    + request.rarity().displayName() + "). Equip all four pieces for the set bonus.");
            if (request.rarity().hasShieldSlot()) {
                send(sender, "<gray>Chestplate shield socket available at " + ArmorMark.MK2.displayName() + "+.");
            }
            return true;
        }

        if (service.definition(request.piece().typeId()).isEmpty()) {
            send(sender, "<red>Infuse " + request.piece().label().toLowerCase(Locale.ROOT)
                    + " is not registered. Is breach bootstrap complete?");
            return true;
        }

        try {
            giveInfusePiece(player, service, request.piece(), request.rarity(), request.armorSet());
        } catch (RuntimeException ex) {
            send(sender, "<red>Failed to create Infuse armor: " + ex.getMessage());
            return true;
        }
        send(sender, "<green>Gave " + request.armorSet().displayName() + " "
                + request.piece().displayName() + " (" + request.rarity().displayName() + "). Equip it to apply "
                + Math.round(request.rarity().defensePercent() * request.piece().defenseShare() * 100.0D)
                + "% defense (piece share).");
        if (request.piece().isChestplate() && request.rarity().hasShieldSlot()) {
            send(sender, "<gray>Shield socket available at " + ArmorMark.MK2.displayName() + "+.");
        }
        return true;
    }

    private record InfuseGiveRequest(
            boolean fullSet,
            InfuseArmorPiece piece,
            GearRarity rarity,
            ArmorSet armorSet
    ) {
    }

    /**
     * Parses {@code /breachitems giveinfuse [piece|set] [rarity] [armor_set]}.
     * Legacy {@code /breachitems giveinfuse <rarity>} still gives a rare chestplate when only rarity is passed.
     */
    private InfuseGiveRequest parseGiveInfuseRequest(CommandSender sender, String[] args) {
        InfuseArmorPiece piece = InfuseArmorPiece.CHESTPLATE;
        GearRarity rarity = GearRarity.RARE;
        ArmorSet armorSet = ArmorSet.VANGUARD;
        boolean fullSet = false;
        int argIndex = 1;

        if (args.length >= 2) {
            String first = args[1].trim().toLowerCase(Locale.ROOT);
            if (isGiveInfuseSetTarget(first)) {
                fullSet = true;
                argIndex = 2;
            } else {
                Optional<InfuseArmorPiece> parsedPiece = InfuseArmorPiece.byId(first);
                if (parsedPiece.isPresent()) {
                    piece = parsedPiece.get();
                    argIndex = 2;
                } else if (isRarityToken(first)) {
                    rarity = GearRarity.valueOf(first.toUpperCase(Locale.ROOT));
                    argIndex = 2;
                } else {
                    send(sender, "<red>Unknown piece. Use: " + String.join(", ", INFUSE_GIVE_TARGETS)
                            + " or a rarity (" + String.join(", ", RARITIES) + ").");
                    return null;
                }
            }
        }

        if (args.length > argIndex) {
            String rarityToken = args[argIndex].trim();
            if (!isRarityToken(rarityToken)) {
                send(sender, "<red>Unknown rarity. Use: " + String.join(", ", RARITIES));
                return null;
            }
            rarity = GearRarity.valueOf(rarityToken.toUpperCase(Locale.ROOT));
            argIndex++;
        }

        if (args.length > argIndex) {
            Optional<ArmorSet> parsedSet = ArmorSet.byId(args[argIndex]);
            if (parsedSet.isEmpty()) {
                send(sender, "<red>Unknown armor set. Use: " + String.join(", ", ARMOR_SETS));
                return null;
            }
            armorSet = parsedSet.get();
        }

        return new InfuseGiveRequest(fullSet, piece, rarity, armorSet);
    }

    private static void giveInfusePiece(
            Player player,
            CustomItemService service,
            InfuseArmorPiece piece,
            GearRarity rarity,
            ArmorSet armorSet
    ) {
        ItemStack stack = ExtractionCustomItemProvider.createInfuseArmor(service, piece, rarity, armorSet);
        if (stack == null || stack.getType().isAir()) {
            throw new IllegalStateException("empty stack for " + piece.name());
        }
        player.getInventory().addItem(stack).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
    }

    private static boolean isGiveInfuseSetTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "set".equals(normalized) || "fullset".equals(normalized);
    }

    private static boolean isRarityToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            GearRarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean handleGiveShield(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null || service.definition(ShieldModuleDefinition.TYPE_ID).isEmpty()) {
            send(sender, "<red>Shield module type is not registered.");
            return true;
        }
        GearRarity rarity = GearRarity.COMMON;
        if (args.length >= 2) {
            try {
                rarity = GearRarity.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                send(sender, "<red>Unknown rarity. Use: " + String.join(", ", RARITIES));
                return true;
            }
        }
        ItemStack shield = ExtractionCustomItemProvider.createShieldModule(service, rarity);
        player.getInventory().addItem(shield).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover)
        );
        send(sender, "<green>Gave shield module (" + rarity.displayName() + "). Requires armor "
                + ArmorMark.requiredForShield(rarity).displayName() + "+.");
        return true;
    }

    private boolean handleGiveBlueprint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text("extraction.command.players_only", ExtractionTexts.defaultLocale()));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "<gray>Usage: /breachitems giveblueprint <recipe_id> [amount]");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        String blueprintId = args[1].trim();
        boolean known = craftingConfig != null && craftingConfig.blueprints().stream()
                .anyMatch(bp -> bp.id().equalsIgnoreCase(blueprintId));
        if (!known) {
            send(sender, "<red>Unknown blueprint id: <white>" + blueprintId);
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                send(sender, "<red>Invalid amount.");
                return true;
            }
        }
        for (int i = 0; i < amount; i++) {
            ItemStack receipt = ExtractionCustomItemProvider.createBlueprintReceipt(service, blueprintId);
            player.getInventory().addItem(receipt).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover)
            );
        }
        send(sender, "<green>Gave <white>" + amount + "x <green>blueprint receipt for <white>" + blueprintId + "<green>.");
        return true;
    }

    private boolean handleSetMark(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "<gray>Usage: /breachitems setmark <mk1|mk2|...|mk6>");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        ArmorMark mark;
        try {
            mark = ArmorMark.parse(args[1]);
        } catch (IllegalArgumentException ex) {
            send(sender, "<red>Unknown mark. Use: " + String.join(", ", MARKS));
            return true;
        }
        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            send(sender, "<red>Wear or hold an Infuse chestplate.");
            return true;
        }
        ShieldSlotRules.Result result = InfuseArmorMutator.setMark(service, player, armor, mark);
        if (result instanceof ShieldSlotRules.Result.Failure failure) {
            send(sender, "<red>" + failure.message());
            return true;
        }
        send(sender, "<green>Set armor mark to " + mark.displayName() + ".");
        return true;
    }

    private boolean handleSetShield(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            send(sender, "<red>Wear or hold an Infuse chestplate.");
            return true;
        }
        ItemStack shield = player.getInventory().getItemInMainHand();
        if (!InfuseArmorMutator.isShieldModule(service, shield)) {
            send(sender, "<red>Hold a shield module in your main hand.");
            return true;
        }
        ShieldSlotRules.Result result = InfuseArmorMutator.socketShield(service, player, armor, shield);
        if (result instanceof ShieldSlotRules.Result.Failure failure) {
            send(sender, "<red>" + failure.message());
            return true;
        }
        if (result instanceof ShieldSlotRules.Result.Success success) {
            send(sender, "<green>Socketed " + success.socketed().displayLabel() + ".");
        }
        return true;
    }

    private boolean handleClearShield(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        if (service == null) {
            send(sender, "<red>Custom item service is unavailable.");
            return true;
        }
        ItemStack armor = InfuseArmorMutator.findInfuseChestplate(service, player).orElse(null);
        if (armor == null) {
            send(sender, "<red>Wear or hold an Infuse chestplate.");
            return true;
        }
        ShieldSlotRules.Result result = InfuseArmorMutator.clearShield(service, player, armor);
        if (result instanceof ShieldSlotRules.Result.Failure failure) {
            send(sender, "<red>" + failure.message());
            return true;
        }
        send(sender, "<green>Removed shield module from armor.");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("skypvp.breach.items")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        CustomItemService service = core.customItemService();
        send(sender, "<aqua>--- Custom Items ---");
        if (service == null) {
            send(sender, "<red>CustomItemService: missing");
            return true;
        }
        send(sender, "<green>CustomItemService: active");

        boolean providerRegistered = false;
        for (RegisteredServiceProvider<CustomItemProvider> registration
                : sender.getServer().getServicesManager().getRegistrations(CustomItemProvider.class)) {
            CustomItemProvider provider = registration.getProvider();
            if (provider instanceof ExtractionCustomItemProvider) {
                providerRegistered = true;
                send(sender, "<green>ExtractionCustomItemProvider: registered (" + provider.definitions().size() + " types)");
                for (CustomItemDefinition definition : provider.definitions()) {
                    send(sender, "<gray>  - " + definition.typeId().uid());
                }
            }
        }
        if (!providerRegistered) {
            send(sender, "<red>ExtractionCustomItemProvider: not registered");
        }

        if (service.definition(InfuseChestplateDefinition.TYPE_ID).isPresent()) {
            send(sender, "<green>extraction/infuse_chestplate: registered");
        } else {
            send(sender, "<red>extraction/infuse_chestplate: not registered");
        }
        if (service.definition(ShieldModuleDefinition.TYPE_ID).isPresent()) {
            send(sender, "<green>extraction/shield_module: registered");
        } else {
            send(sender, "<red>extraction/shield_module: not registered");
        }

        if (sender instanceof Player player) {
            double defense = ExtractionCombatDefense.defenseFraction(core, player);
            send(sender, "<gray>Active defense: " + Math.round(defense * 100.0D) + "% (multiplier "
                    + String.format(Locale.ROOT, "%.2f", ExtractionCombatDefense.damageMultiplier(core, player)) + ")");
            ItemStack chest = player.getInventory().getChestplate();
            if (service.isCustomItem(chest)) {
                service.resolve(chest).ifPresent(instance -> {
                    if (InfuseChestplateDefinition.TYPE_ID.equals(instance.typeId())) {
                        InfuseArmorPayload payload = InfuseArmorPayload.decode(instance.payloadCopy());
                        ArmorMark mark = payload.mark() == null ? ArmorMark.MK1 : payload.mark();
                        send(sender, "<gray>Chestplate: " + payload.rarity().displayName() + " " + mark.displayName());
                        ShieldSocketReference.decode(payload.shieldModule()).ifPresentOrElse(
                                shield -> send(sender, "<gray>Shield socket: " + shield.displayLabel()),
                                () -> {
                                    if (payload.rarity().hasShieldSlot()) {
                                        send(sender, "<gray>Shield socket: empty");
                                    }
                                }
                        );
                    }
                });
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (service.isCustomItem(hand)) {
                service.resolve(hand)
                        .flatMap(instance -> service.definition(instance.typeId()))
                        .filter(definition -> definition.category() == ItemCategory.ARMOR)
                        .ifPresent(definition -> send(sender,
                                "<yellow>  Main hand holds armor — defense applies only when worn"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("skypvp.breach.items")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("giveinfuse", "giveshield", "giverecharger", "givemodule", "givematerials", "giveblueprint", "givebackpack", "setmark", "setshield", "clearshield", "breakshield", "status"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "giveinfuse" -> filterPrefix(giveInfuseTabTargets(args), args[1]);
                case "giveshield" -> filterPrefix(RARITIES, args[1]);
                case "setmark" -> filterPrefix(MARKS, args[1]);
                case "giverecharger" -> filterPrefix(RECHARGER_TIERS, args[1]);
                case "givemodule" -> filterPrefix(MODULE_IDS, args[1]);
                case "givematerials" -> filterPrefix(materialIds(), args[1]);
                case "giveblueprint" -> filterPrefix(blueprintIds(), args[1]);
                case "givebackpack" -> filterPrefix(List.of("1", "2", "3", "4"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && "giveinfuse".equalsIgnoreCase(args[0])) {
            return filterPrefix(giveInfuseTabRarities(args), args[2]);
        }
        if (args.length == 4 && "giveinfuse".equalsIgnoreCase(args[0])) {
            return filterPrefix(ARMOR_SETS, args[3]);
        }
        return List.of();
    }

    /** Tab targets for {@code /breachitems giveinfuse <piece|set|rarity>}. */
    private static List<String> giveInfuseTabTargets(String[] args) {
        List<String> targets = new ArrayList<>(INFUSE_GIVE_TARGETS);
        targets.addAll(RARITIES);
        return targets;
    }

    /** Tab rarities for {@code /breachitems giveinfuse <piece|set> <rarity>}. */
    private static List<String> giveInfuseTabRarities(String[] args) {
        if (args.length < 2) {
            return RARITIES;
        }
        String first = args[1].trim().toLowerCase(Locale.ROOT);
        if (isGiveInfuseSetTarget(first) || InfuseArmorPiece.byId(first).isPresent()) {
            return RARITIES;
        }
        if (isRarityToken(first)) {
            return ARMOR_SETS;
        }
        return RARITIES;
    }

    private List<String> materialIds() {
        if (craftingConfig == null) {
            return List.of();
        }
        return craftingConfig.materials().stream().map(CraftingMaterialDefinition::id).toList();
    }

    private List<String> blueprintIds() {
        if (craftingConfig == null) {
            return List.of();
        }
        return craftingConfig.blueprints().stream().map(bp -> bp.id()).toList();
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
