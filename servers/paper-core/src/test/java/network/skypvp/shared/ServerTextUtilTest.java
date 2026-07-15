package network.skypvp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ServerTextUtilTest {
    @Test
    void applyArgsReplacesIndexedPlaceholders() {
        assertEquals(
                "Joining breach in 5s — stay in the lobby",
                ServerTextUtil.applyArgs("Joining breach in {0}s — stay in the lobby", 5)
        );
    }

    @Test
    void localizedReturnsEnglishCatalogTextRegardlessOfLocale() {
        assertEquals(
                "<red>You are muted.",
                ServerTextUtil.localized("chat.muted", "es_mx")
        );
    }

    @Test
    void toSmallCapsMapsAsciiLetters() {
        assertEquals("ꜱʜᴏᴘ", ServerTextUtil.toSmallCaps("Shop"));
    }

    @Test
    void applySmallCapsTagsConvertsRegionsAndKeepsNestedMiniTags() {
        assertEquals(
                "<gold>ꜱʜᴏᴘ</gold> Keeper",
                ServerTextUtil.applySmallCapsTags("<gold><smallcaps>Shop</smallcaps></gold> Keeper")
        );
        assertEquals(
                "<gold>ꜱʜᴏᴘ</gold>",
                ServerTextUtil.applySmallCapsTags("<smallcaps><gold>Shop</gold></smallcaps>")
        );
    }

    @Test
    void applySmallCapsTagsRespectsEscapedOpenTag() {
        assertEquals(
                "\\<smallcaps>Shop</smallcaps>",
                ServerTextUtil.applySmallCapsTags("\\<smallcaps>Shop</smallcaps>")
        );
    }

    @Test
    void miniMessageComponentAppliesSmallCapsForNpcNames() {
        String plain = PlainTextComponentSerializer.plainText().serialize(
                ServerTextUtil.miniMessageComponent("<gold><smallcaps>Shop Keeper</smallcaps></gold>")
        );
        assertEquals("ꜱʜᴏᴘ ᴋᴇᴇᴘᴇʀ", plain);
        assertFalse(plain.contains("<smallcaps>"));
        assertTrue(plain.contains("ꜱ"));
    }

    @Test
    void miniMessageComponentRendersEscapedSmallCapsLiterally() {
        String plain = PlainTextComponentSerializer.plainText().serialize(
                ServerTextUtil.miniMessageComponent("\\<smallcaps>Shop\\</smallcaps>")
        );
        assertEquals("<smallcaps>Shop</smallcaps>", plain);
    }
}
