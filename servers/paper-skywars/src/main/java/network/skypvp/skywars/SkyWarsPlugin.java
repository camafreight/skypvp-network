package network.skypvp.skywars;

import org.bukkit.plugin.java.JavaPlugin;

public final class SkyWarsPlugin extends JavaPlugin {

    private final SkyWarsMechanicCatalog mechanics = new SkyWarsMechanicCatalog();

    @Override
    public void onEnable() {
        getLogger().info("Loaded mode: " + mechanics.modeKey() + " (" + mechanics.mechanics().size() + " classified mechanics)");
    }
}
