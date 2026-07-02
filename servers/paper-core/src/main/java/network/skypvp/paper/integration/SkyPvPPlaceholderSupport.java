package network.skypvp.paper.integration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.chat.ChatPlaceholderResolver;
import network.skypvp.paper.repository.SocialGraphRepository;
import network.skypvp.paper.repository.PartyRole;
import network.skypvp.paper.repository.NetworkServerDirectoryRepository;
import network.skypvp.paper.service.RankService;
import network.skypvp.shared.BrandStyle;
import network.skypvp.shared.NetworkAnimationEngine;
import network.skypvp.shared.NetworkServerRole;
import network.skypvp.shared.RankRecord;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Shared PlaceholderAPI resolution for {@code skypvp} and {@code skypvpchat}, also used as a
 * fallback inside chat format rendering when PAPI does not replace a token.
 */
public final class SkyPvPPlaceholderSupport {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d/M/yy");
    private static final String SERVER_IP = "skypvp.net";
    private static final Pattern SKYPVP_PATTERN = Pattern.compile("%skypvp_([^%]+)%", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKYPVPCHAT_PATTERN = Pattern.compile("%skypvpchat_([^%]+)%", Pattern.CASE_INSENSITIVE);

    private SkyPvPPlaceholderSupport() {
    }

    public static String resolveSkyPvP(PaperCorePlugin plugin, OfflinePlayer offlinePlayer, String params) {
        String key = toLegacyKey(params);
        if (key == null || key.isBlank()) {
            return "";
        }

        String chatParam = chatParam(key);
        if (chatParam != null) {
            if (offlinePlayer instanceof Player player && player.isOnline()) {
                return ChatPlaceholderResolver.resolve(plugin, player, chatParam);
            }
            return "";
        }

        if (key.startsWith("anim.")) {
            return resolveAnimation(key);
        }

        if (key.startsWith("top_")) {
            return resolveTopStat(plugin, key);
        }

        if (key.startsWith("party.") || key.startsWith("party_")) {
            if (offlinePlayer instanceof Player player && player.isOnline()) {
                return resolveParty(plugin, player, key);
            }
            return resolvePartyAbsent(key);
        }

        if (offlinePlayer == null || !offlinePlayer.isOnline() || !(offlinePlayer.getPlayer() instanceof Player player)) {
            return resolveServerOnly(plugin, key);
        }

        RankService rankService = plugin.rankService();
        RankRecord rank = rankService != null ? rankService.getCached(player.getUniqueId()) : RankRecord.DEFAULT;

        return switch (key) {
            case "server.id" -> plugin.serverId();
            case "server.display" -> compactServerName(plugin.serverId(), plugin.serverRole().name());
            case "server.ip" -> SERVER_IP;
            case "server.role" -> plugin.serverRole().name();
            case "network.online" -> Integer.toString(plugin.getServer().getOnlinePlayers().size());
            case "network.max" -> Integer.toString(plugin.getServer().getMaxPlayers());
            case "network.capacity_label" -> NetworkAnimationEngine.capacityLabel(
                    plugin.getServer().getOnlinePlayers().size(),
                    plugin.getServer().getMaxPlayers()
            );
            case "player.name" -> player.getName();
            case "player.rank_key" -> rank.rankKey() != null && !rank.rankKey().isBlank()
                    ? rank.rankKey().toLowerCase(Locale.ROOT)
                    : "default";
            case "player.rank_label" -> rank.displayName() != null && !rank.displayName().isBlank()
                    ? rank.displayName()
                    : "Player";
            case "player.rank_color" -> BrandStyle.hexForRankKey(rank.rankKey());
            case "player.ping" -> Integer.toString(player.getPing());
            case "player.ping_label" -> pingLabel(player.getPing());
            case "player.ping_color" -> pingColor(player.getPing());
            case "player.coins" -> "0";
            case "world.time_ticks" -> Long.toString(player.getWorld().getTime());
            case "world.time_label" -> minecraftTimeLabel(player.getWorld().getTime());
            case "date" -> LocalDate.now().format(DATE_FORMAT);
            default -> resolveNetworkOnline(plugin, key);
        };
    }

    public static String replacePlaceholders(PaperCorePlugin plugin, Player player, String input) {
        if (input == null || input.indexOf('%') < 0) {
            return input == null ? "" : input;
        }

        String resolved = replacePattern(input, SKYPVP_PATTERN, params -> resolveSkyPvP(plugin, player, params));
        return replacePattern(resolved, SKYPVPCHAT_PATTERN, params -> {
            if (player == null || !player.isOnline()) {
                return "";
            }
            return ChatPlaceholderResolver.resolve(plugin, player, params);
        });
    }

    private static String replacePattern(String input, Pattern pattern, java.util.function.Function<String, String> resolver) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        StringBuffer buffer = new StringBuffer();
        do {
            String replacement = resolver.apply(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        } while (matcher.find());
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String chatParam(String key) {
        if (key.startsWith("chat_")) {
            return key.substring("chat_".length());
        }
        if (key.startsWith("chat.")) {
            return key.substring("chat.".length());
        }
        return null;
    }

    private static String resolveAnimation(String key) {
        long tick = System.currentTimeMillis();
        return switch (key) {
            case "anim.sparkle" -> NetworkAnimationEngine.sparkle(tick);
            case "anim.sparkle_simple" -> NetworkAnimationEngine.sparkleSimple(tick);
            case "anim.dot" -> NetworkAnimationEngine.dot(tick);
            case "anim.arrow" -> NetworkAnimationEngine.arrow(tick);
            case "anim.brand_glare" -> NetworkAnimationEngine.brandGlare(tick);
            case "anim.network_glare" -> NetworkAnimationEngine.networkGlare(tick);
            case "anim.brand_glow" -> NetworkAnimationEngine.brandGlow(tick);
            case "anim.brand_wave" -> NetworkAnimationEngine.brandWave(tick);
            case "anim.brand_shimmer" -> NetworkAnimationEngine.brandShimmer(tick);
            case "anim.brand_pulse" -> NetworkAnimationEngine.brandPulse(tick);
            case "anim.gradient_open" -> NetworkAnimationEngine.brandGradientOpen(tick);
            case "anim.pulse_color" -> NetworkAnimationEngine.pulseAccentHex(tick);
            case "anim.rank_color" -> NetworkAnimationEngine.rankCycleHex(tick);
            case "anim.clock" -> NetworkAnimationEngine.wallClock();
            case "anim.callout" -> NetworkAnimationEngine.footerCallout(tick);
            default -> "";
        };
    }

    private static String resolveTopStat(PaperCorePlugin plugin, String key) {
        if (plugin.playerStatsListener() == null) {
            return "";
        }
        String remainder = key.substring("top_".length());
        boolean isName = remainder.endsWith("_name");
        boolean isValue = remainder.endsWith("_value");
        if (!isName && !isValue) {
            return "";
        }

        String stripped = isName
                ? remainder.substring(0, remainder.length() - 5)
                : remainder.substring(0, remainder.length() - 6);
        int lastUnderscore = stripped.lastIndexOf('_');
        if (lastUnderscore == -1) {
            return "";
        }

        try {
            int rank = Integer.parseInt(stripped.substring(lastUnderscore + 1));
            String statType = stripped.substring(0, lastUnderscore);
            return plugin.playerStatsListener().resolveTopPlaceholder(statType, rank, isName);
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private static String resolveNetworkOnline(PaperCorePlugin plugin, String key) {
        if (key.startsWith("server.online.")) {
            String roleName = key.substring("server.online.".length());
            try {
                NetworkServerRole role = NetworkServerRole.valueOf(roleName.toUpperCase(Locale.ROOT));
                NetworkServerDirectoryRepository repo = plugin.networkServerDirectoryRepository();
                if (repo != null) {
                    return Integer.toString(repo.summarizeRole(role).totalPlayers());
                }
            } catch (IllegalArgumentException ignored) {
            }
            return "0";
        }
        return "";
    }

    private static String resolveServerOnly(PaperCorePlugin plugin, String key) {
        return switch (key) {
            case "server.id" -> plugin.serverId();
            case "server.display" -> compactServerName(plugin.serverId(), plugin.serverRole().name());
            case "server.ip" -> SERVER_IP;
            case "server.role" -> plugin.serverRole().name();
            case "network.online" -> Integer.toString(plugin.getServer().getOnlinePlayers().size());
            case "network.max" -> Integer.toString(plugin.getServer().getMaxPlayers());
            default -> "";
        };
    }

    static String toLegacyKey(String params) {
        if (params == null) {
            return null;
        }

        String normalized = params.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "server_id" -> "server.id";
            case "server_display" -> "server.display";
            case "server_ip" -> "server.ip";
            case "server_role" -> "server.role";
            case "network_online" -> "network.online";
            case "network_max" -> "network.max";
            case "network_capacity_label" -> "network.capacity_label";
            case "player_name" -> "player.name";
            case "player_rank_key" -> "player.rank_key";
            case "player_rank_label" -> "player.rank_label";
            case "player_rank_color" -> "player.rank_color";
            case "player_ping", "ping", "ping_ms" -> "player.ping";
            case "player_ping_label", "ping_label" -> "player.ping_label";
            case "player_ping_color", "ping_color" -> "player.ping_color";
            case "player_coins" -> "player.coins";
            case "world_time_ticks" -> "world.time_ticks";
            case "world_time_label" -> "world.time_label";
            case "lobby_game_state" -> "lobby.game_state";
            case "lobby_player_state" -> "lobby.player_state";
            case "lobby_queue_target" -> "lobby.queue_target";
            case "lobby_player_line" -> "lobby.player_line";
            case "chat_channel", "chat_channel_id" -> "chat.channel";
            case "chat_channel_name", "chat_channel_label" -> "chat.channel_name";
            case "chat_format_prefix" -> "chat.format_prefix";
            case "chat_format_name" -> "chat.format_name";
            case "chat_format_chat_color" -> "chat.format_chat_color";
            case "chat_format_name_color" -> "chat.format_name_color";
            case "date" -> "date";
            case "party_in_party", "party_has_party" -> "party.in_party";
            case "party_leader_name", "party_leader" -> "party.leader_name";
            case "party_follow_leader", "party_follow" -> "party.follow_leader";
            case "party_size", "party_member_count" -> "party.size";
            case "party_role" -> "party.role";
            case "party_is_leader" -> "party.is_leader";
            case "party_is_co_leader" -> "party.is_co_leader";
            case "party_can_invite" -> "party.can_invite";
            default -> normalized.startsWith("anim_")
                    ? "anim." + normalized.substring("anim_".length())
                    : normalized;
        };
    }

    private static String resolvePartyAbsent(String key) {
        String normalized = key.startsWith("party_")
                ? "party." + key.substring("party_".length())
                : key;
        return switch (normalized) {
            case "party.in_party", "party.has_party" -> "false";
            case "party.size", "party.member_count" -> "0";
            case "party.is_leader", "party.is_co_leader", "party.can_invite" -> "false";
            case "party.follow_leader", "party.follow" -> "OFF";
            default -> "";
        };
    }

    private static String resolveParty(PaperCorePlugin plugin, Player player, String key) {
        if (player == null || key == null || key.isBlank()) {
            return "";
        }
        String normalized = key.startsWith("party_")
                ? "party." + key.substring("party_".length())
                : key;
        SocialGraphRepository repository = plugin.socialGraphRepository();
        if (repository == null) {
            return "";
        }
        Optional<SocialGraphRepository.PartySnapshot> partyOpt = repository.partyForMember(player.getUniqueId()).join();
        if (partyOpt.isEmpty()) {
            return switch (normalized) {
                case "party.in_party", "party.has_party" -> "false";
                case "party.size", "party.member_count" -> "0";
                case "party.is_leader", "party.is_co_leader", "party.can_invite" -> "false";
                case "party.follow_leader", "party.follow" -> "OFF";
                default -> "";
            };
        }
        SocialGraphRepository.PartySnapshot party = partyOpt.get();
        String leaderName = party.members().stream()
                .filter(member -> member.playerId().equals(party.leaderId()))
                .map(SocialGraphRepository.PartyMember::username)
                .findFirst()
                .orElse("Unknown");
        SocialGraphRepository.PartyMember self = party.members().stream()
                .filter(member -> member.playerId().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
        PartyRole role = self == null ? PartyRole.MEMBER : self.role();
        return switch (normalized) {
            case "party.in_party", "party.has_party" -> "true";
            case "party.leader_name", "party.leader" -> leaderName;
            case "party.leader_id" -> party.leaderId().toString();
            case "party.follow_leader", "party.follow" -> party.followLeader() ? "ON" : "OFF";
            case "party.size", "party.member_count" -> Integer.toString(party.members().size());
            case "party.role" -> role.displayName();
            case "party.is_leader" -> Boolean.toString(party.leaderId().equals(player.getUniqueId()));
            case "party.is_co_leader" -> Boolean.toString(role == PartyRole.CO_LEADER);
            case "party.can_invite" -> Boolean.toString(role.canInvite());
            case "party.id" -> party.partyId().toString();
            default -> "";
        };
    }

    private static String compactServerName(String serverId, String role) {
        if (serverId == null || serverId.isBlank()) {
            return role == null || role.isBlank() ? "Server" : titleCase(role);
        }
        String normalized = serverId.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("skypvp-")) {
            normalized = normalized.substring("skypvp-".length());
        }
        int podSuffix = normalized.indexOf("-");
        if (podSuffix > 0) {
            normalized = normalized.substring(0, podSuffix);
        }
        return titleCase(normalized.replace('_', ' ').replace('-', ' '));
    }

    private static String titleCase(String input) {
        if (input == null || input.isBlank()) {
            return "Server";
        }
        StringBuilder out = new StringBuilder();
        for (String part : input.trim().split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? "Server" : out.toString();
    }

    private static String pingLabel(int ping) {
        if (ping < 60) {
            return "Excellent";
        }
        if (ping < 120) {
            return "Good";
        }
        if (ping < 200) {
            return "Fair";
        }
        return ping < 350 ? "Poor" : "High";
    }

    private static String pingColor(int ping) {
        if (ping < 60) {
            return "#55FF55";
        }
        if (ping < 120) {
            return "#AAFF55";
        }
        if (ping < 200) {
            return "#FFFF55";
        }
        return ping < 350 ? "#FFAA00" : "#FF5555";
    }

    private static String minecraftTimeLabel(long ticks) {
        long adjusted = (ticks + 6000L) % 24000L;
        long hours = adjusted / 1000L;
        long minutes = adjusted % 1000L * 60L / 1000L;
        return String.format("%02d:%02d", hours, minutes);
    }
}
