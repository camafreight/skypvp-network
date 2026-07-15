package network.skypvp.extraction.item;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Medic station consumables: healing supplies and stamina syringes crafted or purchased at the medic bay.
 */
public enum MedicConsumableType {

    BANDAGE_RAG("bandage_rag", "Bandage Rag", Material.STRING, NamedTextColor.GRAY, 8.0D, 0.0D, 0L, 0L, 0L,
            "Slow wrap for light wounds."),
    STERILE_BANDAGE("sterile_bandage", "Sterile Bandage", Material.WHITE_WOOL, NamedTextColor.WHITE, 16.0D, 0.0D, 0L, 0L, 0L,
            "Sanitized dressing for moderate trauma."),
    MEDKIT("medkit", "Medkit", Material.RED_DYE, NamedTextColor.RED, 30.0D, 0.0D, 0L, 0L, 0L,
            "Field kit for heavy injuries."),
    SURGICAL_KIT("surgical_kit", "Surgical Kit", Material.GOLDEN_APPLE, NamedTextColor.GOLD, 40.0D, 0.0D, 0L, 0L, 0L,
            "Full trauma suite for critical wounds."),
    ADRENALINE_SHOT("adrenaline_shot", "Adrenaline Shot", Material.GLASS_BOTTLE, NamedTextColor.AQUA, 0.0D, 50.0D, 12_000L, 0L, 0L,
            "Instant stamina surge with brief recovery boost."),
    STAMINA_STABILIZER("stamina_stabilizer", "Stamina Stabilizer", Material.HONEY_BOTTLE, NamedTextColor.YELLOW, 0.0D, 0.0D, 0L, 30_000L, 0L,
            "Stops stamina drain for a short window."),
    OVERDRIVE_SERUM("overdrive_serum", "Overdrive Serum", Material.DRAGON_BREATH, NamedTextColor.LIGHT_PURPLE, 0.0D, 25.0D, 18_000L, 4_000L, 0L,
            "Combat serum: regen boost with brief drain immunity.");

    private final String id;
    private final String displayName;
    private final Material material;
    private final NamedTextColor color;
    private final double healAmount;
    private final double staminaRestore;
    private final long regenBoostMillis;
    private final long drainFreezeMillis;
    private final long craftCooldownMillis;
    private final String blurb;
    private final network.skypvp.paper.item.api.CustomItemTypeId typeId;

    MedicConsumableType(
            String id,
            String displayName,
            Material material,
            NamedTextColor color,
            double healAmount,
            double staminaRestore,
            long regenBoostMillis,
            long drainFreezeMillis,
            long craftCooldownMillis,
            String blurb
    ) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.color = color;
        this.healAmount = healAmount;
        this.staminaRestore = staminaRestore;
        this.regenBoostMillis = regenBoostMillis;
        this.drainFreezeMillis = drainFreezeMillis;
        this.craftCooldownMillis = craftCooldownMillis;
        this.blurb = blurb;
        this.typeId = new network.skypvp.paper.item.api.CustomItemTypeId("extraction", "medic_" + id);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material material() {
        return material;
    }

    public NamedTextColor color() {
        return color;
    }

    public double healAmount() {
        return healAmount;
    }

    public double staminaRestore() {
        return staminaRestore;
    }

    public long regenBoostMillis() {
        return regenBoostMillis;
    }

    public long drainFreezeMillis() {
        return drainFreezeMillis;
    }

    public String blurb() {
        return blurb;
    }

    public network.skypvp.paper.item.api.CustomItemTypeId typeId() {
        return typeId;
    }

    public boolean isHealing() {
        return healAmount > 0.0D;
    }

    public boolean isSyringe() {
        return staminaRestore > 0.0D || regenBoostMillis > 0L || drainFreezeMillis > 0L;
    }

    /** Minimum delay between in-raid uses of the same medic supply type. */
    public long useCooldownMillis() {
        return switch (this) {
            case BANDAGE_RAG -> 2_500L;
            case STERILE_BANDAGE -> 4_000L;
            case MEDKIT -> 6_000L;
            case SURGICAL_KIT -> 10_000L;
            case ADRENALINE_SHOT -> 12_000L;
            case STAMINA_STABILIZER -> 15_000L;
            case OVERDRIVE_SERUM -> 20_000L;
        };
    }

    /**
     * HP restored per heal burst after the eat cast finishes. Lower tiers use small chunks; higher tiers
     * restore larger chunks so the bar moves in visible steps rather than a drip.
     */
    public double healChunkAmount() {
        return switch (this) {
            case BANDAGE_RAG -> 2.0D;
            case STERILE_BANDAGE -> 4.0D;
            case MEDKIT -> 6.0D;
            case SURGICAL_KIT -> 10.0D;
            default -> 0.0D;
        };
    }

    /** Ticks between heal bursts while a gradual heal is active (20 ticks = 1s). */
    public long healChunkIntervalTicks() {
        return switch (this) {
            case BANDAGE_RAG -> 10L;
            case STERILE_BANDAGE -> 8L;
            case MEDKIT -> 5L;
            case SURGICAL_KIT -> 4L;
            default -> 10L;
        };
    }

    /** Vanilla eat/drink cast length before the heal (or syringe) effect begins. */
    public float consumeSeconds() {
        return switch (this) {
            case BANDAGE_RAG -> 2.0F;
            case STERILE_BANDAGE -> 1.6F;
            case MEDKIT -> 1.4F;
            case SURGICAL_KIT -> 1.2F;
            case ADRENALINE_SHOT, STAMINA_STABILIZER, OVERDRIVE_SERUM -> 1.0F;
        };
    }

    public String categoryLabel() {
        return isSyringe() ? "Stamina Syringe" : "Healing Supply";
    }

    /** MiniMessage lore/stat lines shared by item tooltips and shop menus. */
    public List<String> statMiniMessageLines() {
        return MedicConsumableTexts.statMiniMessageLines(this);
    }

    public static Optional<MedicConsumableType> byRecipeId(String recipeId) {
        if (recipeId == null || !recipeId.startsWith("medic_")) {
            return Optional.empty();
        }
        return byId(recipeId.substring("medic_".length()));
    }

    public static java.util.Optional<MedicConsumableType> byId(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MedicConsumableType type : values()) {
            if (type.id.equals(normalized)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }

    public static java.util.Optional<MedicConsumableType> byTypeId(network.skypvp.paper.item.api.CustomItemTypeId typeId) {
        if (typeId == null) {
            return java.util.Optional.empty();
        }
        for (MedicConsumableType type : values()) {
            if (type.typeId.equals(typeId)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }

    public static boolean isMedicTypeId(network.skypvp.paper.item.api.CustomItemTypeId typeId) {
        return byTypeId(typeId).isPresent();
    }
}
