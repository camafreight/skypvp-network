package network.skypvp.extraction.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Stateful snapshot of a shield module socketed into an armor's dedicated shield slot. The physical module item is
 * consumed on socket, so all runtime state lives here inside the armor payload.
 *
 * <ul>
 *   <li>{@code currentPoints} — rechargeable buffer remaining (restored to capacity by a shield recharger).</li>
 *   <li>{@code lifetimeAbsorbed} — total damage absorbed over the shield's life.</li>
 *   <li>{@code destroyed} — once {@code lifetimeAbsorbed} reaches integrity the shield is permanently dead and
 *       cannot be recharged until repaired in the armory (future feature).</li>
 * </ul>
 */
public record ShieldSocketReference(
        GearRarity shieldRarity,
        String variantId,
        double currentPoints,
        double lifetimeAbsorbed,
        boolean destroyed
) {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String LEGACY_PREFIX = "shield|";

    public ShieldSocketReference {
        if (shieldRarity == null) {
            shieldRarity = GearRarity.COMMON;
        }
        if (variantId == null || variantId.isBlank()) {
            variantId = "kinetic";
        }
        double capacity = shieldRarity.shieldCapacity();
        if (currentPoints < 0.0D) {
            currentPoints = 0.0D;
        }
        if (currentPoints > capacity) {
            currentPoints = capacity;
        }
        if (lifetimeAbsorbed < 0.0D) {
            lifetimeAbsorbed = 0.0D;
        }
    }

    public static ShieldSocketReference fresh(GearRarity rarity, String variantId) {
        GearRarity resolved = rarity == null ? GearRarity.COMMON : rarity;
        return new ShieldSocketReference(resolved, variantId, resolved.shieldCapacity(), 0.0D, false);
    }

    public static ShieldSocketReference fromModule(ShieldModulePayload module) {
        if (module == null) {
            return fresh(GearRarity.COMMON, "kinetic");
        }
        return fresh(module.shieldRarity(), module.variantId());
    }

    public double maxPoints() {
        return shieldRarity.shieldCapacity();
    }

    public double integrity() {
        return shieldRarity.shieldIntegrity();
    }

    public double remainingIntegrity() {
        return Math.max(0.0D, integrity() - lifetimeAbsorbed);
    }

    public boolean isActive() {
        return !destroyed && currentPoints > 0.0D;
    }

    public boolean isDepleted() {
        return !destroyed && currentPoints <= 0.0D;
    }

    public ShieldSocketReference withState(double newPoints, double newLifetime, boolean nowDestroyed) {
        return new ShieldSocketReference(shieldRarity, variantId, newPoints, newLifetime, nowDestroyed);
    }

    public static Optional<ShieldSocketReference> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        String raw = encoded.trim();
        if (raw.startsWith(LEGACY_PREFIX)) {
            String[] parts = raw.split("\\|", 3);
            if (parts.length < 2) {
                return Optional.empty();
            }
            try {
                GearRarity rarity = GearRarity.valueOf(parts[1].toUpperCase(Locale.ROOT));
                String variant = parts.length >= 3 ? parts[2] : "kinetic";
                return Optional.of(fresh(rarity, variant));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            GearRarity rarity = root.has("shieldRarity")
                    ? GearRarity.valueOf(root.get("shieldRarity").getAsString())
                    : GearRarity.COMMON;
            String variant = root.has("variantId") ? root.get("variantId").getAsString() : "kinetic";
            double current = root.has("currentPoints") ? root.get("currentPoints").getAsDouble() : rarity.shieldCapacity();
            double lifetime = root.has("lifetimeAbsorbed") ? root.get("lifetimeAbsorbed").getAsDouble() : 0.0D;
            boolean destroyed = root.has("destroyed") && root.get("destroyed").getAsBoolean();
            return Optional.of(new ShieldSocketReference(rarity, variant, current, lifetime, destroyed));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public String encode() {
        return GSON.toJson(this);
    }

    public byte[] encodeBytes() {
        return encode().getBytes(StandardCharsets.UTF_8);
    }

    public String variantDisplay() {
        return variantId == null || variantId.isBlank()
                ? "Shield"
                : variantId.substring(0, 1).toUpperCase(Locale.ROOT) + variantId.substring(1) + " Shield";
    }

    public String displayLabel() {
        return variantDisplay() + " (" + shieldRarity.displayName() + ")";
    }
}
