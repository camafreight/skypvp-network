package network.skypvp.paper.currency;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.paper.PaperCorePlugin;

public class CurrencyRegistry {
    private final PaperCorePlugin plugin;
    private final Logger logger;
    private final Map<String, JsonObject> registeredCurrencies;
    private final Gson gson;
    private final File currencyDir;

    public CurrencyRegistry(PaperCorePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.registeredCurrencies = new ConcurrentHashMap<>();
        this.gson = new Gson();
        this.currencyDir = new File(plugin.getDataFolder(), "currencies");
    }

    public void init() {
        this.registerBuiltIn("gold", "Gold", "Premium store currency purchased from the web store");
        this.registerBuiltIn("coins", "Coins", "Soft currency for trading and in-game purchases");

        if (!currencyDir.exists() && !currencyDir.mkdirs()) {
            logger.warning("Could not create currencies directory at " + currencyDir.getAbsolutePath());
            return;
        }

        File[] files = currencyDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            logger.info("No custom currencies found in " + currencyDir.getName());
            return;
        }

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject obj = gson.fromJson(reader, JsonObject.class);
                String id = file.getName().replace(".json", "");
                registeredCurrencies.put(id, obj);
                logger.info("Registered currency: " + id);
            } catch (IOException e) {
                logger.severe("Failed to load currency file: " + file.getName() + " - " + e.getMessage());
            }
        }
    }

    public Map<String, JsonObject> getRegisteredCurrencies() {
        return Collections.unmodifiableMap(registeredCurrencies);
    }

    public java.util.List<String> currencyIds() {
        return registeredCurrencies.keySet().stream().sorted().toList();
    }

    private void registerBuiltIn(String id, String displayName, String description) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", displayName);
        obj.addProperty("description", description);
        obj.addProperty("builtin", true);
        this.registeredCurrencies.put(id, obj);
    }
}
