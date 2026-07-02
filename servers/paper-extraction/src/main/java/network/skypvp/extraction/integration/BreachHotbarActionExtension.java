package network.skypvp.extraction.integration;

import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.paper.gamemode.api.HotbarActionExtension;
import network.skypvp.paper.service.CoreHotbarService;
import org.bukkit.entity.Player;

public final class BreachHotbarActionExtension implements HotbarActionExtension {

    private final BreachEngine engine;

    public BreachHotbarActionExtension(BreachEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean tryHandle(Player player, String action) {
        if (!CoreHotbarService.ACTION_LEAVE_BREACH.equalsIgnoreCase(action)) {
            return false;
        }
        if (!this.engine.isSpectating(player)) {
            return false;
        }
        this.engine.leaveSpectator(player);
        return true;
    }
}
