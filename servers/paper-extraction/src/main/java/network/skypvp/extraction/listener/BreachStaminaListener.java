package network.skypvp.extraction.listener;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gameplay.BreachStaminaSprintBridge;
import network.skypvp.extraction.gameplay.BreachStaminaService;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

public final class BreachStaminaListener implements Listener {

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final BreachStaminaService staminaService;
    private final BreachStaminaSprintBridge sprintBridge;

    public BreachStaminaListener(
            PaperCorePlugin core,
            BreachEngine engine,
            BreachStaminaService staminaService,
            BreachStaminaSprintBridge sprintBridge
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.staminaService = Objects.requireNonNull(staminaService, "staminaService");
        this.sprintBridge = Objects.requireNonNull(sprintBridge, "sprintBridge");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        syncEnrollment(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (shouldCaptureForReconnect(player)) {
            staminaService.captureForReconnect(player.getUniqueId());
        } else {
            staminaService.unenroll(player.getUniqueId());
        }
        sprintBridge.clear(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!staminaService.isEnrolled(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        staminaService.syncFoodBar(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!staminaService.isEnrolled(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        staminaService.syncFoodBar(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleSprint(PlayerToggleSprintEvent event) {
        Player player = event.getPlayer();
        if (!staminaService.isEnrolled(player.getUniqueId())) {
            return;
        }
        // A sprint START must pass the recovery hysteresis, not just "stamina > 0" —
        // otherwise spamming the sprint key at a near-empty pool grants a speed burst
        // per press. canStartSprint ignores the active-sprint grace on purpose.
        if (event.isSprinting() && !staminaService.canStartSprint(player)) {
            event.setCancelled(true);
            sprintBridge.recordSprintIntent(player, false);
            // Belt-and-braces: some clients keep sprint-FOV prediction after a cancelled
            // toggle; reassert the server state so movement speed never desyncs.
            player.setSprinting(false);
            return;
        }
        sprintBridge.recordSprintIntent(player, event.isSprinting());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStarvation(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.STARVATION) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (staminaService.isEnrolled(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    public void tickRaidStamina() {
        if (core.platformScheduler() == null) {
            return;
        }
        // Only live raiders — never fan out to all online players on Folia.
        for (BreachInstance instance : engine.activeInstances()) {
            if (instance.state() != BreachState.ACTIVE) {
                continue;
            }
            for (Player player : instance.liveRaiders()) {
                core.platformScheduler().runOnPlayer(player, () -> tickPlayer(player));
            }
        }
    }

    private void tickPlayer(Player player) {
        if (!isLiveRaider(player)) {
            if (staminaService.isEnrolled(player.getUniqueId())) {
                staminaService.unenroll(player.getUniqueId());
            }
            return;
        }
        if (!staminaService.isEnrolled(player.getUniqueId())) {
            staminaService.enroll(player);
            staminaService.restoreReconnectCapture(player);
        }
        staminaService.tick(player, player.isSprinting(), 0.05D);
    }

    private void syncEnrollment(Player player) {
        if (player == null) {
            return;
        }
        if (isLiveRaider(player)) {
            staminaService.enroll(player);
            staminaService.restoreReconnectCapture(player);
        } else {
            staminaService.clearReconnectCapture(player.getUniqueId());
        }
    }

    private boolean shouldCaptureForReconnect(Player player) {
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty() || BreachLobbyProtection.isLobbySafe(engine, player)) {
            return false;
        }
        BreachInstance breach = instance.get();
        UUID id = player.getUniqueId();
        return breach.state() == BreachState.ACTIVE
                && breach.containsPlayer(id)
                && !breach.hasExtracted(id)
                && !breach.isEliminated(id)
                && !breach.isPendingJoin(id)
                && !engine.isSpectating(player);
    }

    private boolean isLiveRaider(Player player) {
        return shouldCaptureForReconnect(player);
    }
}
