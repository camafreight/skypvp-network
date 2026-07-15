package network.skypvp.lobby;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.gamemode.api.HudProvider.ActionBarContext;
import network.skypvp.paper.gamemode.api.HudProvider.BossBarContext;
import network.skypvp.paper.gamemode.api.HudProvider.BossBarFrame;
import network.skypvp.paper.gamemode.api.HudProvider.ScoreboardContext;
import network.skypvp.paper.gamemode.api.HudProvider.ScoreboardFrame;
import network.skypvp.paper.gamemode.api.HudProvider.TabFrame;
import network.skypvp.paper.gamemode.api.HudProvider.TabListContext;
import network.skypvp.paper.integration.SkyPvPPlaceholderSupport;
import network.skypvp.paper.service.PartyScoreboardData;
import network.skypvp.paper.service.PartyScoreboardLines;
import network.skypvp.paper.tabboard.TabBoardContext;
import network.skypvp.paper.tabboard.TabBoardLines;
import network.skypvp.paper.tabboard.TabBoardSpec;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class LobbyHudProvider implements HudProvider {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String PARTY_FOOTER = "<gradient:gold:yellow><b>ᴘʟᴀʏ.ѕᴋʏᴄʟᴜʙ.ɢɢ";

    private final PaperCorePlugin core;
    private final PartyScoreboardData partyData;

    public LobbyHudProvider() {
        this(null, null);
    }

    public LobbyHudProvider(PartyScoreboardData partyData) {
        this(null, partyData);
    }

    public LobbyHudProvider(PaperCorePlugin core, PartyScoreboardData partyData) {
        this.core = core;
        this.partyData = partyData;
    }

    public String modeKey() {
        return "lobby";
    }

    public Optional<Component> actionBar(ActionBarContext context) {
        return Optional.empty();
    }

    public Optional<ScoreboardFrame> scoreboard(ScoreboardContext context) {
        Player player = context.base().player();
        long tick = context.base().tickMillis();
        String date = LocalDate.now().format(DATE_FORMAT);
        if (this.partyData != null) {
            PartyScoreboardData.PartyView party = this.partyData.get(player.getUniqueId());
            if (party.inParty()) {
                Component title = ServerTextUtil.miniMessageComponent("<gradient:#00c6ff:#0072ff><b>ᴘᴀʀᴛʏ");
                List<Component> body = new ArrayList<>();
                body.add(ServerTextUtil.miniMessageComponent(PartyScoreboardLines.compactHeader(party.onlineCount(), date)));
                int shown = 0;
                for (PartyScoreboardData.MemberView member : party.members()) {
                    if (shown >= PartyScoreboardLines.MAX_MEMBERS) {
                        break;
                    }
                    body.add(PartyScoreboardLines.memberLine(member, PartyScoreboardLines.DEFAULT_DISCONNECTED_GRACE_MILLIS, tick));
                    shown++;
                }
                Component footer = ServerTextUtil.miniMessageComponent(PARTY_FOOTER);
                List<Component> lines = PartyScoreboardLines.buildSidebar(body, footer);
                return Optional.of(new ScoreboardFrame(title, lines));
            }
        }
        Component title = ServerTextUtil.miniMessageComponent(
                "<gradient:#00c6ff:#0072ff><b>" + ServerTextUtil.toSmallCaps("SkyPvP Lobby") + "</b></gradient>"
        );
        List<Component> lines = new ArrayList<>();
        lines.add(ServerTextUtil.miniMessageComponent("<gray>" + date));
        lines.add(ServerTextUtil.miniMessageComponent(
                "<dark_gray>" + ServerTextUtil.toSmallCaps(compactServerName(context.base().serverId()))
        ));
        lines.add(Component.empty());
        lines.add(ServerTextUtil.miniMessageComponent("<gray>Online <white>" + context.base().onlinePlayers()));
        lines.add(ServerTextUtil.miniMessageComponent("<gray>Ping <white>" + player.getPing() + "ms"));
        lines.add(Component.empty());
        lines.add(ServerTextUtil.miniMessageComponent(PARTY_FOOTER));
        return Optional.of(new ScoreboardFrame(title, lines));
    }

    public Optional<TabFrame> tabList(TabListContext context) {
        return Optional.empty();
    }

    @Override
    public Optional<TabBoardSpec> tabBoard(TabListContext context) {
        Player player = context.base().player();
        PartyScoreboardData.PartyView party = this.partyData != null
                ? this.partyData.get(player.getUniqueId())
                : PartyScoreboardData.EMPTY;
        long tick = context.base().tickMillis();
        String date = LocalDate.now().format(DATE_FORMAT);
        List<Component> statLines = List.of(
                ServerTextUtil.miniMessageComponent("<gray>Online <white>" + context.base().onlinePlayers()),
                ServerTextUtil.miniMessageComponent("<gray>Ping <white>" + player.getPing() + "ms")
        );
        List<Player> lobbyPlayers = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != null && online.isOnline() && !online.equals(player)) {
                lobbyPlayers.add(online);
            }
        }
        Component header = ServerTextUtil.miniMessageComponent(
                "<gradient:#00c6ff:#0072ff><b>" + ServerTextUtil.toSmallCaps("SkyPvP Lobby") + "</b></gradient>\n"
                        + "<gray>" + ServerTextUtil.toSmallCaps(compactServerName(context.base().serverId()))
                        + " <dark_gray>| <gray>" + context.base().onlinePlayers() + " online"
                        + " <dark_gray>| <gray>" + player.getPing() + "ms\n"
        );
        Component footer = ServerTextUtil.miniMessageComponent("\n" + PARTY_FOOTER);
        return Optional.of(TabBoardLines.build(new TabBoardContext(
                player,
                party,
                PartyScoreboardLines.DEFAULT_DISCONNECTED_GRACE_MILLIS,
                tick,
                core == null ? null : core.chatFormatService(),
                core == null ? null : core.rankService(),
                statLines,
                lobbyPlayers,
                "lobby",
                header,
                footer
        )));
    }

    public Optional<BossBarFrame> bossBar(BossBarContext context) {
        return Optional.empty();
    }

    private static String compactServerName(String serverId) {
        return SkyPvPPlaceholderSupport.compactServerNameForNavigator(serverId);
    }
}
