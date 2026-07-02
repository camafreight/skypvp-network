package network.skypvp.paper.library.packet;

import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore.Action;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Per-viewer packet-driven sidebar scoreboard. Folia forbids the Bukkit scoreboard API
 * (CraftScoreboard#registerNewTeam throws), so the breach sidebar is rendered purely via
 * PacketEvents. Each line is a score whose own display name carries the text (1.20.3+ format),
 * with a blank score format so the right-side numbers never show — no teams required.
 */
public final class PacketSidebar {

    private static final String OBJECTIVE = "skypvp_sb";
    private static final int SIDEBAR_SLOT = 1;
    private static final int MAX_LINES = 15;
    // Stable, unique score-holder ids per line (hidden behind each score's display name).
    private static final String[] ENTRIES = {
        "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
        "§8", "§9", "§a", "§b", "§c", "§d", "§e"
    };

    private final Plugin plugin;
    private boolean created = false;
    private Component lastTitle;
    private final Component[] lastLines = new Component[MAX_LINES];

    public PacketSidebar(Plugin plugin) {
        this.plugin = plugin;
    }

    public void update(Player player, Component title, List<Component> lines) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Logger logger = this.plugin.getLogger();
        if (!this.created) {
            PacketEventsBridge.send(player, new WrapperPlayServerScoreboardObjective(
                OBJECTIVE, ObjectiveMode.CREATE, title, RenderType.INTEGER, ScoreFormat.blankScore()),
                logger, "sidebar-create");
            PacketEventsBridge.send(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, OBJECTIVE),
                logger, "sidebar-display");
            this.created = true;
            this.lastTitle = title;
        } else if (title != null && !title.equals(this.lastTitle)) {
            PacketEventsBridge.send(player, new WrapperPlayServerScoreboardObjective(
                OBJECTIVE, ObjectiveMode.UPDATE, title, RenderType.INTEGER, ScoreFormat.blankScore()),
                logger, "sidebar-title");
            this.lastTitle = title;
        }

        int count = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < count; i++) {
            Component line = lines.get(i);
            if (!line.equals(this.lastLines[i])) {
                PacketEventsBridge.send(player, new WrapperPlayServerUpdateScore(
                    ENTRIES[i], Action.CREATE_OR_UPDATE_ITEM, OBJECTIVE, MAX_LINES - i, line, ScoreFormat.blankScore()),
                    logger, "sidebar-score");
                this.lastLines[i] = line;
            }
        }
        for (int i = count; i < MAX_LINES; i++) {
            if (this.lastLines[i] != null) {
                PacketEventsBridge.send(player, new WrapperPlayServerUpdateScore(
                    ENTRIES[i], Action.REMOVE_ITEM, OBJECTIVE, Optional.empty()),
                    logger, "sidebar-score-remove");
                this.lastLines[i] = null;
            }
        }
    }

    public void remove(Player player) {
        if (!this.created || player == null || !player.isOnline()) {
            this.created = false;
            return;
        }
        PacketEventsBridge.send(player, new WrapperPlayServerScoreboardObjective(
            OBJECTIVE, ObjectiveMode.REMOVE, Component.empty(), RenderType.INTEGER, ScoreFormat.blankScore()),
            this.plugin.getLogger(), "sidebar-remove");
        this.created = false;
        for (int i = 0; i < MAX_LINES; i++) {
            this.lastLines[i] = null;
        }
        this.lastTitle = null;
    }
}
