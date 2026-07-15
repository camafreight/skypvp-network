package network.skypvp.extraction.gameplay;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Custom "soft" spectator mode for eliminated breach raiders.
 *
 * <p>Vanilla {@link org.bukkit.GameMode#SPECTATOR} blocks item/menu interaction, so eliminated players could
 * not click the leave-breach or menu hotbar items and were forced to type {@code /breach leave}. This service
 * keeps the player in {@link org.bukkit.GameMode#ADVENTURE} (which still delivers {@code PlayerInteractEvent}
 * for hotbar items) but makes them ghost-like: invisible, flying, invulnerable, non-colliding, unable to pick
 * up items, and prevented from dealing damage (enforced by listeners). State is tracked here so all the breach
 * SPECTATOR checks key off {@link #isSpectating(UUID)} rather than the Bukkit game mode.
 *
 * <p>Folia: every entity-state mutation is dispatched to the player's own region thread via the platform
 * scheduler, and we avoid touching other players' entities (invisibility uses a potion effect on the player
 * itself instead of per-viewer hideEntity).
 */
public final class BreachSpectatorService {

    private final ServerPlatform scheduler;
    private final PaperCorePlugin core;
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();

    public BreachSpectatorService(ServerPlatform scheduler, PaperCorePlugin core) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.core = Objects.requireNonNull(core, "core");
    }

    public boolean isSpectating(UUID playerId) {
        return playerId != null && this.spectators.contains(playerId);
    }

    public boolean isSpectating(Player player) {
        return player != null && this.isSpectating(player.getUniqueId());
    }

    /**
     * Puts {@code player} into soft-spectator mode. When {@code vantage} is non-null the player is teleported
     * there first (used for void deaths so the ghost does not respawn inside the void).
     */
    public void enter(Player player, Location vantage) {
        if (player == null) {
            return;
        }
        this.spectators.add(player.getUniqueId());
        this.scheduler.runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (vantage != null && vantage.getWorld() != null) {
                player.teleportAsync(vantage);
            }
            this.applySpectatorState(player);
            this.applySpectatorHotbar(player);
        });
        // Fully remove the ghost from every other client (TAB list + entity), so live players cannot see,
        // target, hit, or proximity-hear them. Invisibility alone leaves them in TAB and still interactable.
        this.hideFromEveryone(player);
    }

    /** Restores normal play state. Idempotent — safe to call on any player returning to the hub. */
    public void exit(Player player) {
        if (player == null) {
            return;
        }
        boolean wasSpectating = this.spectators.remove(player.getUniqueId());
        if (!wasSpectating) {
            return;
        }
        this.scheduler.runOnPlayer(player, () -> {
            if (player.isOnline()) {
                this.clearSpectatorState(player);
            }
        });
        this.showToEveryone(player);
    }

    /** Quit cleanup: drop tracking without scheduling entity work on a leaving player. */
    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        boolean wasSpectating = this.spectators.remove(player.getUniqueId());
        if (wasSpectating) {
            // Clear the hidden-state on every viewer so this UUID is visible again if they reconnect.
            this.showToEveryone(player);
        }
    }

    /**
     * Re-hides every active spectator from a player who just connected. TAB is global, so without this a
     * freshly-joined hub player would still see eliminated raiders in their player list.
     */
    public void hideActiveSpectatorsFrom(Player viewer) {
        if (viewer == null || this.spectators.isEmpty()) {
            return;
        }
        this.scheduler.runOnPlayer(viewer, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            for (UUID specId : this.spectators) {
                if (specId.equals(viewer.getUniqueId())) {
                    continue;
                }
                Player spec = Bukkit.getPlayer(specId);
                if (spec != null && spec.isOnline()) {
                    viewer.hidePlayer(this.core, spec);
                }
            }
        });
    }

    private void hideFromEveryone(Player spectator) {
        UUID specId = spectator.getUniqueId();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(specId)) {
                continue;
            }
            this.scheduler.runOnPlayer(viewer, () -> {
                if (viewer.isOnline() && spectator.isOnline()) {
                    viewer.hidePlayer(this.core, spectator);
                }
            });
        }
    }

    private void showToEveryone(Player spectator) {
        UUID specId = spectator.getUniqueId();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(specId)) {
                continue;
            }
            this.scheduler.runOnPlayer(viewer, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                viewer.showPlayer(this.core, spectator);
                // showPlayer re-lists the player in TAB and drops the hide-vanilla-nametag team.
                if (this.core.tabBoardService() != null) {
                    this.core.tabBoardService().rehideRealPlayersIfBoardActive(viewer);
                }
            });
        }
        if (this.core.nametagLibrary() != null && spectator.isOnline()) {
            this.core.nametagLibrary().resyncTarget(spectator);
            this.scheduler.runOnPlayerLater(spectator, () -> {
                if (spectator.isOnline() && this.core.nametagLibrary() != null) {
                    this.core.nametagLibrary().resyncTarget(spectator);
                }
            }, 2L);
        }
    }

    private void applySpectatorState(Player player) {
        BreachPlayerVitality.restore(player);
        // ADVENTURE (not SPECTATOR) so the leave/menu hotbar items still deliver PlayerInteractEvent, and not
        // SURVIVAL so the ghost cannot break blocks.
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setCanPickupItems(false);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvisible(true);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    private void clearSpectatorState(Player player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setInvisible(false);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setCollidable(true);
        player.setCanPickupItems(true);
        player.setInvulnerable(false);
        BreachPlayerVitality.restore(player);
    }

    private void applySpectatorHotbar(Player player) {
        if (this.core.coreHotbarService() == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        this.core.coreHotbarService().ensureSpectatorHotbar(player);
    }
}
