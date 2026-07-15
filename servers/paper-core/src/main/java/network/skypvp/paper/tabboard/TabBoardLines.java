package network.skypvp.paper.tabboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.service.PartyScoreboardData;
import network.skypvp.paper.service.PartyScoreboardLines;
import network.skypvp.paper.service.RankService;
import network.skypvp.shared.RankRecord;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Builds the 4x20 tab-board grid: every cell is its own tab entry with its own head icon and
 * ping bars, laid out by the vanilla client (see {@link TabBoardLayout}).
 *
 * <p>Column 0 = party roster, column 1 = viewer stats, columns 2-3 = server/breach players.</p>
 */
public final class TabBoardLines {

    private static final String HEADER_COLOR = "<#00c6ff>";
    /** Pixels reserved at the right of a party cell for the disconnect grace timer. */
    private static final int TIMER_RESERVE_PIXELS = 28;
    /** Breathing room kept free at the right edge of every cell. */
    private static final int CELL_GAP_PIXELS = ServerTextUtil.SPACE_PIXEL_WIDTH;
    /** Full green bars for non-player cells, matching the polished-board look. */
    private static final int FILLER_LATENCY_MS = 5;
    /** Player cells available across columns 2-3 (one row is the column header). */
    private static final int PLAYER_CELL_CAPACITY = TabBoardLayout.ROWS * 2 - 1;

    private TabBoardLines() {
    }

    public static TabBoardSpec build(TabBoardContext context) {
        if (context == null) {
            return TabBoardSpec.empty();
        }
        List<Cell> partyCol = partyColumn(context);
        List<Cell> statsCol = statsColumn(context);
        List<Cell> playerCells = playerCells(context);

        List<TabBoardEntry> entries = new ArrayList<>(TabBoardLayout.CELL_COUNT);
        for (int row = 0; row < TabBoardLayout.ROWS; row++) {
            entries.add(entry(0, row, partyCol.get(row)));
            entries.add(entry(1, row, statsCol.get(row)));
            entries.add(entry(2, row, playerCells.get(row)));
            entries.add(entry(3, row, playerCells.get(TabBoardLayout.ROWS + row)));
        }
        return new TabBoardSpec(entries, context.header(), context.footer());
    }

    /**
     * One grid cell. The occupant key feeds the fake profile id, so a cell whose occupant
     * changes is removed and re-added (the only way to swap its skin), while text-only changes
     * on the same occupant stay flicker-free display-name updates.
     */
    private record Cell(String occupantKey, Component text, TabBoardSkins.Texture texture, int latencyMs) {

        private static final Cell EMPTY = new Cell("s", Component.empty(), TabBoardSkins.gray(), FILLER_LATENCY_MS);

        static Cell empty() {
            return EMPTY;
        }

        static Cell of(Component text) {
            return new Cell("s", text, TabBoardSkins.gray(), FILLER_LATENCY_MS);
        }

        static Cell mini(String miniMessage) {
            return of(ServerTextUtil.miniMessageComponent(miniMessage));
        }
    }

    private static TabBoardEntry entry(int col, int row, Cell cell) {
        Cell safe = cell == null ? Cell.empty() : cell;
        return new TabBoardEntry(
                TabBoardService.profileId("cell", col + ":" + row + ":" + safe.occupantKey()),
                TabBoardLayout.sortName(col, row),
                cellText(safe.text()),
                Math.max(1, safe.latencyMs()),
                true
        ).withTexture(safe.texture());
    }

    /** Truncates and pads to the fixed cell width so all four columns stay uniform. */
    private static Component cellText(Component content) {
        Component safe = content == null ? Component.empty() : content;
        return ServerTextUtil.padToWidth(
                ServerTextUtil.truncateComponentToWidth(safe, TabBoardLayout.CELL_TEXT_WIDTH_PIXELS - CELL_GAP_PIXELS),
                TabBoardLayout.CELL_TEXT_WIDTH_PIXELS,
                ServerTextUtil.HAlign.LEFT
        );
    }

    private static Cell headerCell(String label, String suffixMiniMessage) {
        String text = HEADER_COLOR + "<b>" + ServerTextUtil.toSmallCaps(label) + "</b>"
                + (suffixMiniMessage == null || suffixMiniMessage.isBlank() ? "" : " " + suffixMiniMessage);
        return Cell.mini(text);
    }

    // ------------------------------------------------------------------ party

    private static List<Cell> partyColumn(TabBoardContext context) {
        List<Cell> cells = new ArrayList<>(TabBoardLayout.ROWS);
        PartyScoreboardData.PartyView party = context.party();
        Player viewer = context.viewer();
        boolean inParty = party != null && party.inParty();
        int count = inParty ? party.onlineCount() : 1;
        cells.add(headerCell("party", "<gray>" + count + "/" + PartyScoreboardLines.MAX_MEMBERS));

        if (inParty) {
            List<PartyScoreboardData.MemberView> members = party.members();
            for (int i = 0; i < members.size() && cells.size() < TabBoardLayout.ROWS; i++) {
                boolean lastRow = cells.size() == TabBoardLayout.ROWS - 1;
                int remaining = members.size() - i;
                if (lastRow && remaining > 1) {
                    cells.add(Cell.mini("<gray>+" + remaining + " more"));
                    break;
                }
                cells.add(memberCell(members.get(i), context));
            }
        } else if (viewer != null) {
            cells.add(selfCell(viewer, context.chatFormats(), context.rankService()));
            cells.add(Cell.mini("<dark_gray>/party invite"));
        }
        padColumn(cells);
        return cells;
    }

    private static Cell memberCell(PartyScoreboardData.MemberView member, TabBoardContext context) {
        String occupant = member.playerId() != null ? member.playerId().toString() : "n:" + member.name();
        Component text = memberDisplay(
                member,
                context.graceMillis(),
                context.nowEpochMillis(),
                context.chatFormats(),
                context.rankService()
        );
        return new Cell(occupant, text, TabBoardSkins.forMember(member), memberLatency(member));
    }

    private static int memberLatency(PartyScoreboardData.MemberView member) {
        if (member.playerId() != null) {
            Player online = Bukkit.getPlayer(member.playerId());
            if (online != null && online.isOnline()) {
                return Math.max(1, online.getPing());
            }
        }
        return FILLER_LATENCY_MS;
    }

    private static Cell selfCell(Player viewer, ChatFormatService chatFormats, RankService rankService) {
        UUID id = viewer.getUniqueId();
        RankRecord rank = rankService == null ? RankRecord.DEFAULT : rankService.getCached(id);
        Component identity = chatFormats == null
                ? ServerTextUtil.miniMessageComponent("<white>" + viewer.getName())
                : chatFormats.renderPlayerListIdentity(id, viewer.getName(), rank);
        Component line = Component.empty()
                .append(ServerTextUtil.miniMessageComponent("<green>\u25CF "))
                .append(identity);
        return new Cell(id.toString(), line, TabBoardSkins.fromPlayer(viewer), Math.max(1, viewer.getPing()));
    }

    private static Component memberDisplay(
            PartyScoreboardData.MemberView member,
            long graceMillis,
            long nowEpochMillis,
            ChatFormatService chatFormats,
            RankService rankService
    ) {
        Component identity;
        if (chatFormats != null && rankService != null && member.playerId() != null) {
            RankRecord rank = rankService.getCached(member.playerId());
            identity = chatFormats.renderPlayerListIdentity(member.playerId(), member.name(), rank);
        } else {
            identity = ServerTextUtil.miniMessageComponent(PartyScoreboardLines.memberLinePlainName(member));
        }
        Component dot = ServerTextUtil.miniMessageComponent(presenceDot(member));
        Component line = Component.empty().append(dot).append(Component.space()).append(identity);
        if (member.presence() == PartyScoreboardData.Presence.DISCONNECTED && member.disconnectedSinceEpochMillis() > 0L) {
            long safeGrace = graceMillis > 0L ? graceMillis : PartyScoreboardLines.DEFAULT_DISCONNECTED_GRACE_MILLIS;
            long remainingMs = member.disconnectedSinceEpochMillis() + safeGrace - nowEpochMillis;
            String timer = PartyScoreboardLines.formatGraceRemaining(remainingMs);
            String timerColor = remainingMs <= 60_000L ? "<red>" : "<yellow>";
            Component timerPart = ServerTextUtil.miniMessageComponent(timerColor + timer);
            int width = TabBoardLayout.CELL_TEXT_WIDTH_PIXELS - CELL_GAP_PIXELS;
            // Cap the name segment so the right-aligned timer always fits inside the cell.
            Component cappedLine = ServerTextUtil.truncateComponentToWidth(line, width - TIMER_RESERVE_PIXELS);
            return ServerTextUtil.layoutThreeZone(cappedLine, Component.empty(), timerPart, width);
        }
        return line;
    }

    private static String presenceDot(PartyScoreboardData.MemberView member) {
        return switch (member.presence()) {
            case ONLINE -> "<green>\u25CF";
            case DISCONNECTED -> "<gold>\u25CF";
            default -> "<dark_gray>\u25CF";
        };
    }

    // ------------------------------------------------------------------ stats

    private static List<Cell> statsColumn(TabBoardContext context) {
        List<Cell> cells = new ArrayList<>(TabBoardLayout.ROWS);
        cells.add(headerCell("stats", null));
        List<Component> lines = context.statLines();
        for (int i = 0; i < lines.size() && cells.size() < TabBoardLayout.ROWS; i++) {
            cells.add(Cell.of(lines.get(i)));
        }
        padColumn(cells);
        return cells;
    }

    // ---------------------------------------------------------------- players

    /** Columns 2-3 flattened: header cell first, then players top-to-bottom across both columns. */
    private static List<Cell> playerCells(TabBoardContext context) {
        List<Player> players = context.nearbyPlayers().stream()
                .filter(p -> p != null && p.isOnline())
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<Cell> cells = new ArrayList<>(TabBoardLayout.ROWS * 2);
        cells.add(headerCell(context.playersLabel(), "<gray>" + players.size()));
        boolean overflow = players.size() > PLAYER_CELL_CAPACITY;
        int visible = overflow ? PLAYER_CELL_CAPACITY - 1 : players.size();
        for (int i = 0; i < visible; i++) {
            cells.add(playerCell(players.get(i), context));
        }
        if (overflow) {
            cells.add(Cell.mini("<gray>+" + (players.size() - visible) + " more"));
        }
        while (cells.size() < TabBoardLayout.ROWS * 2) {
            cells.add(Cell.empty());
        }
        return cells;
    }

    private static Cell playerCell(Player other, TabBoardContext context) {
        UUID id = other.getUniqueId();
        RankRecord rank = context.rankService() == null ? RankRecord.DEFAULT : context.rankService().getCached(id);
        Component identity = context.chatFormats() == null
                ? ServerTextUtil.miniMessageComponent("<white>" + other.getName())
                : context.chatFormats().renderPlayerListIdentity(id, other.getName(), rank);
        return new Cell(id.toString(), identity, TabBoardSkins.fromPlayer(other), Math.max(1, other.getPing()));
    }

    // ------------------------------------------------------------------ misc

    private static void padColumn(List<Cell> cells) {
        while (cells.size() < TabBoardLayout.ROWS) {
            cells.add(Cell.empty());
        }
    }

    /** Compact party header for the non-board {@code TabFrame} fallback path. */
    public static Component partyHeader(PartyScoreboardData.PartyView party, String date) {
        if (party == null || !party.inParty()) {
            return Component.empty();
        }
        return ServerTextUtil.miniMessageComponent(
                "<gradient:#00c6ff:#0072ff><b>Party</b></gradient> <dark_gray>| "
                        + PartyScoreboardLines.compactHeader(party.onlineCount(), date)
        );
    }
}
