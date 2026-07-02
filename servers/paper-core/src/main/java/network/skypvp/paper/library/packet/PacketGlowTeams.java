package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.paper.platform.Platforms;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Applies colored entity glow outlines via per-viewer scoreboard team packets.
 * Real world entities on Folia use {@link #applyGlow}; packet-only NPC bodies use
 * {@link #applyPacketEntityTeam} on every platform because Bukkit scoreboard teams
 * do not recolor their client-side packet entities.
 */
public final class PacketGlowTeams {

    private static final ConcurrentHashMap<String, GlowSignature> ACTIVE_GLOWS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, GlowSignature> ACTIVE_PACKET_TEAMS = new ConcurrentHashMap<>();

    private PacketGlowTeams() {
    }

    public static boolean usePacketGlow(Plugin plugin) {
        return Platforms.get(plugin).isFolia();
    }

    public static NamedTextColor resolveColor(boolean glow, String glowColor) {
        if (!glow || glowColor == null || glowColor.isBlank()) {
            return NamedTextColor.WHITE;
        }
        String clean = glowColor.toLowerCase(Locale.ROOT).replaceAll("[<>]", "");
        try {
            return (NamedTextColor) NamedTextColor.NAMES.value(clean);
        } catch (Exception ignored) {
            return NamedTextColor.WHITE;
        }
    }

    public static void applyGlow(Plugin plugin, Player viewer, String entry, boolean glow, String glowColor) {
        if (!usePacketGlow(plugin) || viewer == null || !viewer.isOnline() || entry == null || entry.isBlank()) {
            return;
        }
        sendTeamPacket(plugin, viewer, entry, glow, glowColor, false, ACTIVE_GLOWS);
    }

    /**
     * Per-viewer team for packet-spawned NPC bodies (fake players/mobs). Also hides nametags when requested.
     */
    public static void applyPacketEntityTeam(
            Plugin plugin,
            Player viewer,
            String entry,
            boolean glowing,
            String glowColor,
            boolean hideNameTag
    ) {
        if (viewer == null || !viewer.isOnline() || entry == null || entry.isBlank()) {
            return;
        }
        if (!glowing && !hideNameTag) {
            removePacketEntityTeam(plugin, viewer, entry);
            return;
        }
        sendTeamPacket(
                plugin,
                viewer,
                entry,
                glowing,
                glowColor,
                hideNameTag,
                ACTIVE_PACKET_TEAMS
        );
    }

    /**
     * Re-sends the per-viewer team even when the cached signature is unchanged. Packet NPCs often need this
     * after spawn because the first team packet can arrive before the client has registered the profile.
     */
    public static void refreshPacketEntityTeam(
            Plugin plugin,
            Player viewer,
            String entry,
            boolean glowing,
            String glowColor,
            boolean hideNameTag
    ) {
        if (viewer == null || !viewer.isOnline() || entry == null || entry.isBlank()) {
            return;
        }
        if (!glowing && !hideNameTag) {
            removePacketEntityTeam(plugin, viewer, entry);
            return;
        }
        ACTIVE_PACKET_TEAMS.remove(cacheKey(viewer, entry));
        applyPacketEntityTeam(plugin, viewer, entry, glowing, glowColor, hideNameTag);
    }

    public static void removeGlow(Plugin plugin, Player viewer, String entry) {
        if (!usePacketGlow(plugin) || viewer == null || !viewer.isOnline() || entry == null || entry.isBlank()) {
            return;
        }
        removeTeamPacket(plugin, viewer, entry, ACTIVE_GLOWS);
    }

    public static void removePacketEntityTeam(Plugin plugin, Player viewer, String entry) {
        if (viewer == null || !viewer.isOnline() || entry == null || entry.isBlank()) {
            return;
        }
        removeTeamPacket(plugin, viewer, entry, ACTIVE_PACKET_TEAMS);
    }

    private static void sendTeamPacket(
            Plugin plugin,
            Player viewer,
            String entry,
            boolean glowing,
            String glowColor,
            boolean hideNameTag,
            ConcurrentHashMap<String, GlowSignature> cache
    ) {
        String cacheKey = cacheKey(viewer, entry);
        GlowSignature next = new GlowSignature(glowing, glowColor, hideNameTag);
        GlowSignature previous = cache.get(cacheKey);
        if (next.equals(previous)) {
            return;
        }
        cache.put(cacheKey, next);

        Logger logger = plugin.getLogger();
        String teamName = uniqueTeamName(entry);
        NamedTextColor color = resolveColor(glowing, glowColor);
        ScoreBoardTeamInfo info = new ScoreBoardTeamInfo(
            Component.empty(),
            Component.empty(),
            Component.empty(),
            hideNameTag ? NameTagVisibility.NEVER : NameTagVisibility.ALWAYS,
            CollisionRule.NEVER,
            color,
            OptionData.NONE
        );
        if (previous == null) {
            PacketEventsBridge.send(
                viewer,
                new WrapperPlayServerTeams(teamName, TeamMode.CREATE, info),
                logger,
                "entity-glow-team-create"
            );
            PacketEventsBridge.send(
                viewer,
                new WrapperPlayServerTeams(teamName, TeamMode.ADD_ENTITIES, java.util.Optional.empty(), entry),
                logger,
                "entity-glow-team-add"
            );
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerTeams(teamName, TeamMode.UPDATE, info),
            logger,
            "entity-glow-team-update"
        );
    }

    private static void removeTeamPacket(
            Plugin plugin,
            Player viewer,
            String entry,
            ConcurrentHashMap<String, GlowSignature> cache
    ) {
        String cacheKey = cacheKey(viewer, entry);
        if (cache.remove(cacheKey) == null) {
            return;
        }
        PacketEventsBridge.send(
            viewer,
            new WrapperPlayServerTeams(uniqueTeamName(entry), TeamMode.REMOVE, java.util.Optional.empty()),
            plugin.getLogger(),
            "entity-glow-team-remove"
        );
    }

    private static String cacheKey(Player viewer, String entry) {
        return viewer.getUniqueId() + "\0" + entry;
    }

    private static String uniqueTeamName(String entry) {
        String compact = entry.replace("-", "");
        if (compact.length() > 12) {
            compact = compact.substring(0, 12);
        }
        return "npc_ge_" + compact;
    }

    private record GlowSignature(boolean glow, String glowColor, boolean hideNameTag) {
        private GlowSignature(boolean glow, String glowColor) {
            this(glow, glowColor, false);
        }

        private GlowSignature {
            glowColor = glowColor == null || glowColor.isBlank() ? null : glowColor.trim().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GlowSignature signature
                    && this.glow == signature.glow
                    && this.hideNameTag == signature.hideNameTag
                    && Objects.equals(this.glowColor, signature.glowColor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(glow, glowColor, hideNameTag);
        }
    }
}
