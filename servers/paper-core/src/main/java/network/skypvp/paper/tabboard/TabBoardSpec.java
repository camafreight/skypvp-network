package network.skypvp.paper.tabboard;

import java.util.List;
import net.kyori.adventure.text.Component;

/** Full tab canvas for one viewer: the 4x20 cell entries plus the polished header/footer. */
public record TabBoardSpec(List<TabBoardEntry> entries, Component header, Component footer) {

    public static TabBoardSpec empty() {
        return new TabBoardSpec(List.of(), Component.empty(), Component.empty());
    }

    public TabBoardSpec {
        entries = entries == null ? List.of() : List.copyOf(entries);
        header = header == null ? Component.empty() : header;
        footer = footer == null ? Component.empty() : footer;
    }
}
