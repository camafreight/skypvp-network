package network.skypvp.paper.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PaperPunishmentRepository;
import network.skypvp.shared.PunishmentRecord;
import network.skypvp.shared.ChatFormatRefreshEvent;
import network.skypvp.shared.chat.ChatFormatFlags;
import network.skypvp.shared.chat.ChatFormatProfile;
import network.skypvp.shared.chat.ChatFormatScope;
import network.skypvp.shared.chat.ChatMessageRenderer;
import network.skypvp.shared.chat.ChatModerationAction;
import network.skypvp.shared.chat.ChatModerationVerdict;
import network.skypvp.shared.chat.ChatRenderContext;
import network.skypvp.shared.chat.TextModerationClient;
import network.skypvp.shared.chat.TextModerationResult;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class ChatFormatService {
    @FunctionalInterface
    public interface ChangeListener {
        void onFormatsChanged(String formatId, String action);
    }

    private final PaperCorePlugin plugin;
    private final ChatFormatRepository repository;
    private final Logger logger;
    private final Map<String, ChatFormatProfile> rankFormats = new ConcurrentHashMap<>();
    private final Map<ChatFormatScope, ChatFormatFlags> systemDefaults = new EnumMap<>(ChatFormatScope.class);
    private volatile ChangeListener changeListener;

    public ChatFormatService(PaperCorePlugin plugin, ChatFormatRepository repository, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
        this.loadYamlDefaults();
    }

    public void setChangeListener(ChangeListener changeListener) {
        this.changeListener = changeListener;
    }

    public void reload() {
        this.loadYamlDefaults();
        this.repository.loadAll().thenAccept(profiles -> {
            this.rankFormats.clear();
            for (ChatFormatProfile profile : profiles) {
                if (profile.scope() == ChatFormatScope.RANK) {
                    this.rankFormats.put(profile.id().toLowerCase(), profile);
                } else {
                    this.systemDefaults.put(profile.scope(), profile.flags());
                }
            }
            this.logger.info("[chat] Loaded " + profiles.size() + " format profile(s).");
        }).exceptionally(ex -> {
            this.logger.warning("[chat] Failed to reload formats: " + ex.getMessage());
            return null;
        });
    }

    public CompletableFuture<Void> upsert(ChatFormatProfile profile) {
        return this.repository.upsert(profile).thenRun(() -> {
            if (profile.scope() == ChatFormatScope.RANK) {
                this.rankFormats.put(profile.id().toLowerCase(), profile);
            } else {
                this.systemDefaults.put(profile.scope(), profile.flags());
            }
            this.notifyChanged(profile.id(), ChatFormatRefreshEvent.ACTION_UPSERT);
        });
    }

    public CompletableFuture<Boolean> remove(String formatId) {
        return this.repository.remove(formatId).thenApply(removed -> {
            if (removed) {
                this.rankFormats.remove(formatId.toLowerCase());
                this.notifyChanged(formatId, ChatFormatRefreshEvent.ACTION_REMOVE);
            }
            return removed;
        });
    }

    public List<ChatFormatProfile> rankFormats() {
        return this.rankFormats.values().stream()
                .sorted(Comparator.comparingInt((ChatFormatProfile profile) -> profile.flags().priority()).reversed())
                .toList();
    }

    public ChatFormatFlags resolveRankFormat(Player player) {
        ChatFormatFlags best = this.systemDefaults.getOrDefault(ChatFormatScope.RANK, ChatFormatFlags.EMPTY);
        int bestPriority = Integer.MIN_VALUE;
        for (ChatFormatProfile profile : this.rankFormats.values()) {
            if (!ChatPermissionChecks.hasFormatPermission(player, profile.id())) {
                continue;
            }
            if (profile.flags().priority() >= bestPriority) {
                bestPriority = profile.flags().priority();
                best = profile.flags();
            }
        }
        return best;
    }

    public ChatFormatFlags systemFormat(ChatFormatScope scope) {
        return this.systemDefaults.getOrDefault(scope, ChatFormatFlags.EMPTY);
    }

    public Component render(Player player, ChatFormatFlags flags, String message, String channelLabel) {
        ChatRenderContext context = new ChatRenderContext(player.getName(), message, channelLabel);
        return ChatRenderContextHolder.callWith(
                context,
                () -> ChatMessageRenderer.render(
                        flags,
                        context,
                        text -> ChatPlaceholderBridge.apply(player, text, context)
                )
        );
    }

    public Component renderPlayerListName(Player player) {
        if (player == null) {
            return Component.empty();
        }
        ChatFormatFlags flags = resolveRankFormat(player);
        ChatRenderContext context = new ChatRenderContext(
                player.getName(),
                "",
                network.skypvp.shared.chat.ChatChannel.ALL.displayName()
        );
        return ChatRenderContextHolder.callWith(
                context,
                () -> ChatMessageRenderer.renderIdentity(
                        flags,
                        context,
                        text -> ChatPlaceholderBridge.apply(player, text, context)
                )
        );
    }

    public Component renderNamed(String playerName, ChatFormatFlags flags, String message, String channelLabel) {
        ChatRenderContext context = new ChatRenderContext(playerName, message, channelLabel);
        return ChatMessageRenderer.render(
                flags,
                context,
                text -> ChatPlaceholderBridge.apply(text, context)
        );
    }

    public Component renderRemote(String playerName, ChatFormatScope scope, String message) {
        return renderNamed(
                playerName,
                systemFormat(scope),
                message,
                channelLabel(scope)
        );
    }

    private static String channelLabel(ChatFormatScope scope) {
        return switch (scope) {
            case PARTY -> network.skypvp.shared.chat.ChatChannel.PARTY.displayName();
            case STAFF -> network.skypvp.shared.chat.ChatChannel.STAFF.displayName();
            case PRIVATE -> network.skypvp.shared.chat.ChatChannel.PRIVATE.displayName();
            case RANK -> network.skypvp.shared.chat.ChatChannel.ALL.displayName();
        };
    }

    public Optional<ChatFormatProfile> findRankFormat(String id) {
        return Optional.ofNullable(this.rankFormats.get(id.toLowerCase()));
    }

    private void loadYamlDefaults() {
        this.systemDefaults.clear();
        this.systemDefaults.put(ChatFormatScope.RANK, flagsFromSection(this.plugin.getConfig().getConfigurationSection("chat.defaults.rank")));
        this.systemDefaults.put(ChatFormatScope.PRIVATE, flagsFromSection(this.plugin.getConfig().getConfigurationSection("chat.defaults.private")));
        this.systemDefaults.put(ChatFormatScope.PARTY, flagsFromSection(this.plugin.getConfig().getConfigurationSection("chat.defaults.party")));
        this.systemDefaults.put(ChatFormatScope.STAFF, ChatFormatFlags.EMPTY);
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(
                            Objects.requireNonNull(this.plugin.getResource("chat-formats-defaults.yml")),
                            java.nio.charset.StandardCharsets.UTF_8
                    )
            );
            mergeDefaults(ChatFormatScope.RANK, yaml.getConfigurationSection("chat.defaults.rank"));
            mergeDefaults(ChatFormatScope.PRIVATE, yaml.getConfigurationSection("chat.defaults.private"));
            mergeDefaults(ChatFormatScope.PARTY, yaml.getConfigurationSection("chat.defaults.party"));
        } catch (Exception ignored) {
        }
    }

    private void mergeDefaults(ChatFormatScope scope, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        this.systemDefaults.put(scope, flagsFromSection(section));
    }

    private static ChatFormatFlags flagsFromSection(ConfigurationSection section) {
        if (section == null) {
            return ChatFormatFlags.EMPTY;
        }
        return new ChatFormatFlags(
                section.getInt("priority", 0),
                section.getString("prefix", ""),
                section.getString("name_color", section.getString("name-color", "")),
                section.getString("name", ""),
                section.getString("suffix", ""),
                section.getString("chat_color", section.getString("chat-color", "")),
                section.getString("channel_tooltip", ""),
                section.getString("prefix_tooltip", ""),
                section.getString("name_tooltip", ""),
                section.getString("suffix_tooltip", ""),
                section.getString("prefix_click_command", ""),
                section.getString("name_click_command", ""),
                section.getString("suffix_click_command", "")
        );
    }

    private void notifyChanged(String formatId, String action) {
        ChangeListener listener = this.changeListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onFormatsChanged(formatId, action);
        } catch (Exception ex) {
            this.logger.warning("[chat] Format change listener failed: " + ex.getMessage());
        }
    }
}
