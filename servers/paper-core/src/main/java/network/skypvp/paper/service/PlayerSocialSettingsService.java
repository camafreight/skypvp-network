package network.skypvp.paper.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.model.PlayerSocialSettings;
import network.skypvp.paper.repository.PlayerSocialSettingsRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import network.skypvp.shared.SocialChatRules;
import network.skypvp.shared.chat.ChatChannel;

public final class PlayerSocialSettingsService {
    @FunctionalInterface
    public interface ChangeListener {
        void onSettingsChanged(UUID playerId);
    }

    public static final String PERMISSION_CHAT_TOGGLE_BYPASS = SocialChatRules.PERMISSION_CHAT_TOGGLE_BYPASS;

    private final PlayerSocialSettingsRepository repository;
    private final ConcurrentHashMap<UUID, PlayerSocialSettings> cache = new ConcurrentHashMap<>();
    private volatile ChangeListener changeListener;

    public PlayerSocialSettingsService(PlayerSocialSettingsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public void setChangeListener(ChangeListener changeListener) {
        this.changeListener = changeListener;
    }

    public CompletableFuture<Void> preload(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.repository.load(playerId).thenAccept(settings -> this.cache.put(playerId, settings));
    }

    public void evict(UUID playerId) {
        if (playerId != null) {
            this.cache.remove(playerId);
        }
    }

    public PlayerSocialSettings get(UUID playerId) {
        if (playerId == null) {
            return PlayerSocialSettings.defaults(UUID.randomUUID());
        }
        PlayerSocialSettings cached = this.cache.get(playerId);
        return cached != null ? cached : PlayerSocialSettings.defaults(playerId);
    }

    public boolean isLoaded(UUID playerId) {
        return playerId != null && this.cache.containsKey(playerId);
    }

    public boolean isAutoTranslateEnabled(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        PlayerSocialSettings settings = this.cache.get(playerId);
        return settings != null && settings.autoTranslateEnabled();
    }

    public CompletableFuture<PlayerSocialSettings> refresh(UUID playerId) {
        return this.repository.load(playerId).thenApply(settings -> {
            this.cache.put(playerId, settings);
            return settings;
        });
    }

    public CompletableFuture<PlayerSocialSettings> update(UUID playerId, PlayerSocialSettings settings) {
        if (playerId == null || settings == null) {
            return CompletableFuture.completedFuture(PlayerSocialSettings.defaults(UUID.randomUUID()));
        }
        this.cache.put(playerId, settings);
        String username = Optional.ofNullable(Bukkit.getPlayer(playerId)).map(Player::getName).orElse("Unknown");
        return this.repository.save(settings, username).thenApply(ignored -> {
            this.notifyChanged(playerId);
            return settings;
        });
    }

    public boolean isChatEnabled(UUID playerId) {
        return this.get(playerId).chatEnabled();
    }

    public boolean blocksFriendRequests(UUID playerId) {
        return this.get(playerId).blockFriendRequests();
    }

    public boolean blocksPartyRequests(UUID playerId) {
        return this.get(playerId).blockPartyRequests();
    }

    public boolean isProfanityFilterEnabled(UUID playerId) {
        return this.get(playerId).profanityFilterEnabled();
    }

    public ChatChannel activeChatChannel(UUID playerId) {
        return this.get(playerId).activeChatChannel();
    }

    public CompletableFuture<PlayerSocialSettings> setActiveChatChannel(UUID playerId, ChatChannel channel) {
        return this.update(playerId, this.get(playerId).withActiveChatChannel(channel));
    }

    public Optional<PlayerSocialSettings> cached(UUID playerId) {
        return Optional.ofNullable(this.cache.get(playerId));
    }

    private void notifyChanged(UUID playerId) {
        ChangeListener listener = this.changeListener;
        if (listener == null || playerId == null) {
            return;
        }
        try {
            listener.onSettingsChanged(playerId);
        } catch (Exception ignored) {
        }
    }
}
