package network.skypvp.paper.nms;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Fallback used when the NMS headless-player backend is unavailable. Every operation is a safe no-op. */
public final class NoopHeadlessPlayerService implements HeadlessPlayerService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean hang(Player player) {
        return false;
    }

    @Override
    public boolean spawn(HeadlessPlayerSpec spec) {
        return false;
    }

    @Override
    public boolean exists(UUID id) {
        return false;
    }

    @Override
    public boolean isHeadless(UUID id) {
        return false;
    }

    @Override
    public Optional<HeadlessSnapshot> capture(UUID id) {
        return Optional.empty();
    }

    @Override
    public Optional<HeadlessSnapshot> removeAndCapture(UUID id) {
        return Optional.empty();
    }

    @Override
    public boolean remove(UUID id) {
        return false;
    }

    @Override
    public Optional<HeadlessSnapshot> evictForLogin(UUID id, Location regionHint) {
        return Optional.empty();
    }

    @Override
    public boolean forceClearLoginSlot(UUID id) {
        return true;
    }

    @Override
    public Set<UUID> activeIds() {
        return Set.of();
    }

    @Override
    public void shutdown() {
    }
}
