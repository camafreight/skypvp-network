package network.skypvp.shared.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public final class ChatFormatJsonCodec {
    private static final Gson GSON = new Gson();

    private ChatFormatJsonCodec() {
    }

    public static ChatFormatFlags jsonToFlags(String raw) {
        if (raw == null || raw.isBlank()) {
            return ChatFormatFlags.EMPTY;
        }
        JsonObject json = GSON.fromJson(raw, JsonObject.class);
        if (json == null) {
            return ChatFormatFlags.EMPTY;
        }
        ChatFormatFlags flags = new ChatFormatFlags(
                json.has("priority") ? json.get("priority").getAsInt() : 0,
                text(json, "prefix"),
                text(json, "name_color"),
                text(json, "name"),
                text(json, "suffix"),
                text(json, "chat_color"),
                text(json, "channel_tooltip"),
                text(json, "prefix_tooltip"),
                text(json, "name_tooltip"),
                text(json, "suffix_tooltip"),
                text(json, "prefix_click_command"),
                text(json, "name_click_command"),
                text(json, "suffix_click_command")
        );
        return flags;
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
