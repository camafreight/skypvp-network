package network.skypvp.extraction.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;

public record ShieldRechargerPayload(
        RechargerTier tier
) {

    private static final Gson GSON = new GsonBuilder().create();

    public ShieldRechargerPayload {
        if (tier == null) {
            tier = RechargerTier.FIELD;
        }
    }

    public static ShieldRechargerPayload of(RechargerTier tier) {
        return new ShieldRechargerPayload(tier);
    }

    public static ShieldRechargerPayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return of(RechargerTier.FIELD);
        }
        try {
            ShieldRechargerPayload decoded = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), ShieldRechargerPayload.class);
            return decoded == null ? of(RechargerTier.FIELD) : decoded;
        } catch (RuntimeException ignored) {
            return of(RechargerTier.FIELD);
        }
    }

    public byte[] encode() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }
}
