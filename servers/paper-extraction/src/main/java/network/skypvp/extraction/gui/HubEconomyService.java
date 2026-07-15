package network.skypvp.extraction.gui;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PlayerCurrencyRepository;
import org.bukkit.entity.Player;

/** Coin and gold spending/refunds for hub workstations (armory, medic bay, stash upgrades). */
public final class HubEconomyService {

    private final PaperCorePlugin core;

    public HubEconomyService(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    public CompletableFuture<Boolean> trySpendCoins(Player player, long amount) {
        if (player == null || amount <= 0L) {
            return CompletableFuture.completedFuture(amount <= 0L);
        }
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID id = player.getUniqueId();
        repository.ensurePlayer(id);
        return repository.trySpendCoins(id, amount);
    }

    public CompletableFuture<Boolean> trySpendGold(Player player, long amount) {
        if (player == null || amount <= 0L) {
            return CompletableFuture.completedFuture(true);
        }
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID id = player.getUniqueId();
        repository.ensurePlayer(id);
        return repository.trySpendGold(id, amount);
    }

    public void refundCoins(UUID playerId, long amount) {
        if (playerId == null || amount <= 0L) {
            return;
        }
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository != null) {
            repository.addCoins(playerId, amount);
        }
    }

    public void refundGold(UUID playerId, long amount) {
        if (playerId == null || amount <= 0L) {
            return;
        }
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository != null) {
            repository.addGold(playerId, amount);
        }
    }

    public long cachedCoins(UUID playerId) {
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository == null || playerId == null) {
            return 0L;
        }
        return repository.getBalance(playerId).map(PlayerCurrencyRepository.Balance::coins).orElse(0L);
    }

    public long cachedGold(UUID playerId) {
        PlayerCurrencyRepository repository = core.playerCurrencyRepository();
        if (repository == null || playerId == null) {
            return 0L;
        }
        return repository.getBalance(playerId).map(PlayerCurrencyRepository.Balance::gold).orElse(0L);
    }
}
