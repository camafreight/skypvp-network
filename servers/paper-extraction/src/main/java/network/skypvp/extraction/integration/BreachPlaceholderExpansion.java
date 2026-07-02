package network.skypvp.extraction.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import network.skypvp.extraction.engine.BreachEngine;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class BreachPlaceholderExpansion extends PlaceholderExpansion {

    private final BreachPlaceholderResolver resolver;

    public BreachPlaceholderExpansion(BreachEngine engine) {
        this.resolver = new BreachPlaceholderResolver(engine);
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skypvp_breach";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SkyPvP";
    }

    @Override
    public @NotNull String getVersion() {
        return "2.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        return resolver.resolve(offlinePlayer, params);
    }
}
