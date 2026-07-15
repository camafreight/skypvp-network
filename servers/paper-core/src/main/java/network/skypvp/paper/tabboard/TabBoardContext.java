package network.skypvp.paper.tabboard;

import java.util.List;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.service.PartyScoreboardData;
import network.skypvp.paper.service.RankService;
import org.bukkit.entity.Player;

/** Inputs for {@link TabBoardLines#build(TabBoardContext)}. */
public record TabBoardContext(
        Player viewer,
        PartyScoreboardData.PartyView party,
        long graceMillis,
        long nowEpochMillis,
        ChatFormatService chatFormats,
        RankService rankService,
        List<Component> statLines,
        List<Player> nearbyPlayers,
        String playersLabel,
        Component header,
        Component footer
) {
    public TabBoardContext {
        statLines = statLines == null ? List.of() : List.copyOf(statLines);
        nearbyPlayers = nearbyPlayers == null ? List.of() : List.copyOf(nearbyPlayers);
        playersLabel = playersLabel == null || playersLabel.isBlank() ? "Players" : playersLabel;
        header = header == null ? Component.empty() : header;
        footer = footer == null ? Component.empty() : footer;
    }
}
