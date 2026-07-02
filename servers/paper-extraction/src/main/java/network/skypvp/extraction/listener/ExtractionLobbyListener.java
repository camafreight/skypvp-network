package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Optional;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ExtractionLobbyListener implements Listener {

    private final BreachEngine engine;

    public ExtractionLobbyListener(BreachEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (this.engine.instanceFor(player).isPresent()) {
            return;
        }
        Optional<BreachInstance> worldInstance = this.engine.instanceForWorld(player.getWorld());
        if (worldInstance.isPresent()) {
            this.engine.forceReturnFromBreachWorld(player, worldInstance.get());
            return;
        }
        if (player.getWorld().getName().startsWith("breach_")) {
            this.engine.returnStrandedPlayerToHub(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (BreachLobbyProtection.isLobbySafe(engine, player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (BreachLobbyProtection.isLobbySafe(engine, player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }
}
