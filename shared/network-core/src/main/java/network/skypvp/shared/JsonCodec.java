package network.skypvp.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class JsonCodec {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private JsonCodec() {
    }

    public static Gson gson() {
        return GSON;
    }
}
