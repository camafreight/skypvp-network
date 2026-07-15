package network.skypvp.extraction.crafting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;

/** Payload for physical hub crafting material stacks ({@code extraction:crafting_material}). */
public record CraftingMaterialItemPayload(String materialId) {

    private static final Gson GSON = new GsonBuilder().create();

    public CraftingMaterialItemPayload {
        if (materialId == null) {
            materialId = "";
        }
    }

    public static CraftingMaterialItemPayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new CraftingMaterialItemPayload("");
        }
        try {
            CraftingMaterialItemPayload decoded = GSON.fromJson(
                    new String(payload, StandardCharsets.UTF_8),
                    CraftingMaterialItemPayload.class
            );
            return decoded == null ? new CraftingMaterialItemPayload("") : decoded;
        } catch (RuntimeException ignored) {
            return new CraftingMaterialItemPayload("");
        }
    }

    public byte[] encode() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }
}
