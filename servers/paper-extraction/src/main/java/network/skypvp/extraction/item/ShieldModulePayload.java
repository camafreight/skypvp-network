package network.skypvp.extraction.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public record ShieldModulePayload(
        GearRarity shieldRarity,
        String variantId
) {

    private static final Gson GSON = new GsonBuilder().create();

    public ShieldModulePayload {
        if (shieldRarity == null) {
            shieldRarity = GearRarity.COMMON;
        }
        if (variantId == null || variantId.isBlank()) {
            variantId = "kinetic";
        }
    }

    public static ShieldModulePayload defaults(GearRarity rarity) {
        return new ShieldModulePayload(rarity, "kinetic");
    }

    public static ShieldModulePayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return defaults(GearRarity.COMMON);
        }
        try {
            ShieldModulePayload decoded = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), ShieldModulePayload.class);
            return decoded == null ? defaults(GearRarity.COMMON) : decoded;
        } catch (RuntimeException ignored) {
            return defaults(GearRarity.COMMON);
        }
    }

    public byte[] encode() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }
}
