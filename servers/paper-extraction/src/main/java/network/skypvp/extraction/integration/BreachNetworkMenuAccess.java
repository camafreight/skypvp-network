package network.skypvp.extraction.integration;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.paper.gamemode.api.NetworkMenuAccess;
import org.bukkit.entity.Player;

public final class BreachNetworkMenuAccess implements NetworkMenuAccess {

    private static final Set<String> RAID_LOCKED_KEYS = Set.of("PARTY", "LOADOUTS", "LOADOUT", "VAULT", "REWARDS");

    private final BreachEngine engine;

    public BreachNetworkMenuAccess(BreachEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean isHubSubmenuLocked(Player player, String submenuKey) {
        if (player == null || submenuKey == null) {
            return false;
        }
        if (!RAID_LOCKED_KEYS.contains(submenuKey.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return this.isActiveRaider(player);
    }

    @Override
    public String hubSubmenuLockReason(Player player) {
        return "Locked while you are in an active breach raid.";
    }

    private boolean isActiveRaider(Player player) {
        if (!player.getWorld().getName().startsWith("breach_")) {
            return false;
        }
        Optional<BreachInstance> instance = this.engine.instanceFor(player);
        if (instance.isEmpty()) {
            return true;
        }
        BreachInstance breach = instance.get();
        UUID playerId = player.getUniqueId();
        if (breach.isPendingJoin(playerId) || breach.hasExtracted(playerId) || breach.isEliminated(playerId)) {
            return false;
        }
        if (this.engine.isSpectating(player)) {
            return false;
        }
        return breach.state() == BreachState.ACTIVE;
    }
}
