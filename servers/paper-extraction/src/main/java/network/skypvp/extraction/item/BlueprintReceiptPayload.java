package network.skypvp.extraction.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public record BlueprintReceiptPayload(String blueprintId) {

    private static final Gson GSON = new GsonBuilder().create();

    public BlueprintReceiptPayload {
        if (blueprintId == null) {
            blueprintId = "";
        }
    }

    public static BlueprintReceiptPayload of(String blueprintId) {
        return new BlueprintReceiptPayload(blueprintId == null ? "" : blueprintId.trim());
    }

    public static BlueprintReceiptPayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return of("");
        }
        try {
            BlueprintReceiptPayload decoded = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), BlueprintReceiptPayload.class);
            return decoded == null ? of("") : decoded;
        } catch (RuntimeException ignored) {
            return of("");
        }
    }

    public byte[] encode() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }
}
