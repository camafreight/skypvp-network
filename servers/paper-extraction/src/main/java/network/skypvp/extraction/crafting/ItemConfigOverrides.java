package network.skypvp.extraction.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import network.skypvp.extraction.item.MedicConsumableType;
import network.skypvp.paper.item.api.CustomItemTypeId;

/** Runtime bridge from {@link ItemConfigService} into static lore/stat contributors. */
public final class ItemConfigOverrides {

    private static volatile Map<String, JsonObject> overrides = Map.of();
    private static volatile MedicShopConfigService medicShop;

    private ItemConfigOverrides() {
    }

    public static void bind(ItemConfigService service) {
        overrides = service == null ? Map.of() : service.overrides();
    }

    public static void bindMedicShop(MedicShopConfigService service) {
        medicShop = service;
    }

    public static Optional<JsonObject> config(CustomItemTypeId typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(overrides.get(typeId.path()));
    }

    public static Optional<String> displayName(CustomItemTypeId typeId) {
        return text(typeId, "displayName");
    }

    public static Optional<String> blurb(CustomItemTypeId typeId) {
        return text(typeId, "blurb");
    }

    public static double defenseMultiplier(CustomItemTypeId typeId) {
        return config(typeId)
                .flatMap(root -> optionalDouble(root, "defenseMultiplier"))
                .filter(value -> value > 0.0D)
                .orElse(1.0D);
    }

    public static List<String> loreLines(CustomItemTypeId typeId) {
        return config(typeId)
                .map(root -> stringList(root, "loreLines"))
                .orElse(List.of());
    }

    public static List<ModuleEffectLine> moduleEffectLines(CustomItemTypeId typeId) {
        return config(typeId)
                .map(ItemConfigOverrides::parseModuleEffects)
                .orElse(List.of());
    }

    public static double medicHeal(MedicConsumableType type) {
        return medicDouble(type, "healAmount", type.healAmount());
    }

    public static double medicStaminaRestore(MedicConsumableType type) {
        return medicDouble(type, "staminaRestore", type.staminaRestore());
    }

    public static long medicRegenBoostMillis(MedicConsumableType type) {
        return medicLong(type, "regenBoostMillis", type.regenBoostMillis());
    }

    public static long medicDrainFreezeMillis(MedicConsumableType type) {
        return medicLong(type, "drainFreezeMillis", type.drainFreezeMillis());
    }

    public static long medicUseCooldownMillis(MedicConsumableType type) {
        return medicLong(type, "useCooldownMillis", type.useCooldownMillis());
    }

    public static double medicHealChunk(MedicConsumableType type) {
        return medicDouble(type, "healChunkAmount", type.healChunkAmount());
    }

    public static long medicHealChunkIntervalTicks(MedicConsumableType type) {
        return medicLong(type, "healChunkIntervalTicks", type.healChunkIntervalTicks());
    }

    public static float medicConsumeSeconds(MedicConsumableType type) {
        double value = medicDouble(type, "consumeSeconds", type.consumeSeconds());
        return (float) Math.max(0.4D, value);
    }

    /** Max stack size for a medic consumable (stats.stackSize in crafting/items/medic_<id>.json). */
    public static int medicStackSize(MedicConsumableType type) {
        long value = medicLong(type, "stackSize", 8L);
        return (int) Math.max(1L, Math.min(64L, value));
    }

    public static long medicCoinCost(MedicConsumableType type) {
        if (medicShop != null) {
            long shopCost = medicShop.coinCost(type);
            if (shopCost > 0L) {
                return shopCost;
            }
        }
        return config(type.typeId())
                .flatMap(root -> optionalLong(root, "coinCost"))
                .or(() -> config(type.typeId()).flatMap(root -> optionalLong(statsObject(root), "coinCost")))
                .orElse(defaultMedicCoinCost(type));
    }

    public static Optional<MedicShopConfigService.MaterialCost> medicMaterialCost(MedicConsumableType type) {
        if (medicShop == null) {
            return Optional.empty();
        }
        return medicShop.materialCost(type);
    }

    private static long defaultMedicCoinCost(MedicConsumableType type) {
        return switch (type) {
            case BANDAGE_RAG -> 25L;
            case STERILE_BANDAGE -> 50L;
            case MEDKIT -> 120L;
            case SURGICAL_KIT -> 250L;
            case ADRENALINE_SHOT -> 150L;
            case STAMINA_STABILIZER -> 180L;
            case OVERDRIVE_SERUM -> 220L;
        };
    }

    private static double medicDouble(MedicConsumableType type, String key, double fallback) {
        return config(type.typeId())
                .flatMap(root -> optionalDouble(statsObject(root), key))
                .orElse(fallback);
    }

    private static long medicLong(MedicConsumableType type, String key, long fallback) {
        return config(type.typeId())
                .flatMap(root -> optionalLong(statsObject(root), key))
                .orElse(fallback);
    }

    private static JsonObject statsObject(JsonObject root) {
        if (root.has("stats") && root.get("stats").isJsonObject()) {
            return root.getAsJsonObject("stats");
        }
        return root;
    }

    private static Optional<String> text(CustomItemTypeId typeId, String key) {
        return config(typeId).flatMap(root -> {
            if (!root.has(key) || root.get(key).isJsonNull()) {
                return Optional.empty();
            }
            String value = root.get(key).getAsString().trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        });
    }

    private static Optional<Double> optionalDouble(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = object.getAsJsonPrimitive(key);
        if (!primitive.isNumber()) {
            return Optional.empty();
        }
        return Optional.of(primitive.getAsDouble());
    }

    private static Optional<Long> optionalLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return Optional.empty();
        }
        JsonPrimitive primitive = object.getAsJsonPrimitive(key);
        if (!primitive.isNumber()) {
            return Optional.empty();
        }
        return Optional.of(primitive.getAsLong());
    }

    private static List<String> stringList(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray(key)) {
            if (element.isJsonPrimitive()) {
                String line = element.getAsString().trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static List<ModuleEffectLine> parseModuleEffects(JsonObject root) {
        if (!root.has("moduleEffects") || !root.get("moduleEffects").isJsonArray()) {
            return List.of();
        }
        List<ModuleEffectLine> lines = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("moduleEffects")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String label = entry.has("label") && !entry.get("label").isJsonNull()
                    ? entry.get("label").getAsString().trim()
                    : "";
            if (label.isEmpty()) {
                continue;
            }
            boolean positive = !entry.has("positive") || entry.get("positive").getAsBoolean();
            lines.add(new ModuleEffectLine(label, positive));
        }
        return List.copyOf(lines);
    }

    public record ModuleEffectLine(String label, boolean positive) {
    }
}
