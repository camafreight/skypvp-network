package network.skypvp.paper.tabboard;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.UUID;
import network.skypvp.paper.service.PartyScoreboardData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Signed skin textures for fake tab-board rows. */
public final class TabBoardSkins {

    /** Gray skin from [MineSkin 9e541f29](https://minesk.in/9e541f29eb384c63b36f62e362cfb7c8). */
    public static final String GRAY_TEXTURE_VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc4MzI2ODI0NTI5MCwKICAicHJvZmlsZUlkIiA6ICI0NDAzZGM1NDc1YmM0YjE1YTU0OGNmZGE2YjBlYjdkOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJBdW50YnJpeCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85NjQyZjUwNTdiZjc5NWMyOGFkMjhhY2ViNTk1YzkwZmFjODY1ZWE4YTI5NGUzNjYxNTE1ZTg4ZWM1NThjZGFjIgogICAgfQogIH0KfQ==";
    public static final String GRAY_TEXTURE_SIGNATURE =
            "E/1UJc81V0fgEeUAd3Uba5FPO6OWxKTX/9FJGJ2trsE2fX0o9J/w5TiUy5MdxKcIftGlKDumyY9ns6HSEA6wIc6kyLeRoB44V4v5dzbQXM5/Vrnv588fIqYrTiqJdapepmCrpWbT1P9IhSZKYqb+L5+qIlCmVOi38WSv3cd+sf3Cy0SinNLoYM6QIFCy/lT+v54fS4zCbssqB6tKCoKqgWT6eG6DI2Zubxe1HIiAsKBgLOrDON2zunq/0tnpf1LUfLl54D3eue+c3Z89BY8jPHPGdaGMMO39USDb8zbWPdRiczPprnfrsSKTaLp6CCZnYaOUUcPy8FN4tx8vmYq/0lb22CsvdAUsJW7vz76EXI8gnhBW1SxGT2tlC1bskwpzzGfnJqRFGZ+zPOtM7SKF7LBzuQE5aCk159HhID1KZJnA3kiUAI7lOAnyZbU9oIz0xPVaxXL2sHFj7/XcQwkBzy8eDbp3SG1QFMurMXboCd9PzC1MsdxEuHhgD1nF8tEgJz91sYskbjfjFL2yoBEKJCGdgRF+W0+5mbrM2a3tET0FOojDlcdCok/488BRouek6Bf2r3rz0Fm64evgTuEz9LoSYb8yd+hO4X3frhqa7bkoWKj5w4LqoRj3XCFu7PBn28RcwvyaTJKjL67v9EPVz7lMgu2BfQ5xbf4rtPCWJK4=";

    private TabBoardSkins() {
    }

    public record Texture(String value, String signature) {
        public Texture {
            value = value == null ? "" : value;
            signature = signature == null ? "" : signature;
        }

        public boolean present() {
            return !value.isBlank();
        }
    }

    public static Texture gray() {
        return new Texture(GRAY_TEXTURE_VALUE, GRAY_TEXTURE_SIGNATURE);
    }

    public static Texture fromPlayer(Player player) {
        if (player == null) {
            return gray();
        }
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName()) && property.getValue() != null && !property.getValue().isBlank()) {
                return new Texture(property.getValue(), property.getSignature());
            }
        }
        return gray();
    }

    public static Texture forMember(PartyScoreboardData.MemberView member) {
        if (member == null || member.playerId() == null) {
            return gray();
        }
        Player online = Bukkit.getPlayer(member.playerId());
        if (online != null && online.isOnline()) {
            return fromPlayer(online);
        }
        return gray();
    }
}
