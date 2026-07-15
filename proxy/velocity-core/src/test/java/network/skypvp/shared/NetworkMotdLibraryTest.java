package network.skypvp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class NetworkMotdLibraryTest {

    @Test
    void centerTextPadsPlainAscii() {
        // MOTD centering pads with leading spaces only (trailing spaces render as drift on some clients).
        String centered = NetworkMotdLibrary.centerText("SKYCLUB", 11);
        assertEquals("  SKYCLUB", centered);
    }

    @Test
    void centerMotdLineUsesLeadingSpacesOnly() {
        String line = NetworkMotdLibrary.networkNameSection("1.20–1.21.11");
        Component raw = ServerTextUtil.miniMessageComponent(line);
        Component centered = ServerTextUtil.centerMotdMiniMessageLine(line);
        int rawWidth = ServerTextUtil.componentVisibleWidth(raw);
        int centeredWidth = ServerTextUtil.componentVisibleWidth(centered);
        assertTrue(centeredWidth > rawWidth);
        int leadingSpaces = centeredWidth - rawWidth;
        assertTrue(leadingSpaces >= (ServerTextUtil.MOTD_LINE_WIDTH_PIXELS - rawWidth) / 2 - 8);
        assertTrue(leadingSpaces <= (ServerTextUtil.MOTD_LINE_WIDTH_PIXELS - rawWidth) / 2 + 8);
    }

    @Test
    void promoTaglineForRefreshIsStaticPerQuery() {
        String first = NetworkMotdLibrary.promoTaglineForRefresh(1_000L);
        String sameWindow = NetworkMotdLibrary.promoTaglineForRefresh(30_000L);
        String later = NetworkMotdLibrary.promoTaglineForRefresh(70_000L);
        assertFalse(first.isBlank());
        assertEquals(first, sameWindow);
        assertFalse(later.isBlank());
    }

    @Test
    void buildDescriptionUsesStaticBrandWithoutAnimatedTags() {
        NetworkMotdLibrary.Snapshot snapshot = NetworkMotdLibrary.Snapshot.builder()
            .refreshEpochMillis(1_000L)
            .proxyOnlinePlayers(128)
            .maxPlayers(5000)
            .extractionPods(2)
            .openBreachSlots(18)
            .build();

        String line1 = snapshot.customLine1() != null ? snapshot.customLine1() : NetworkMotdLibrary.networkNameSection(snapshot.versionRange());
        assertFalse(line1.contains("<anim:"));
        assertTrue(line1.contains("SKYCLUB"));

        String plain = PlainTextComponentSerializer.plainText().serialize(NetworkMotdLibrary.buildDescription(snapshot));
        assertTrue(plain.toLowerCase().contains("skyclub"));
    }

    @Test
    void composerAllowsCustomPromoOverride() {
        String line = NetworkMotdLibrary.compose()
            .refreshAt(2_000L)
            .players(42, 5000)
            .promo("CUSTOM EVENT LIVE")
            .promoStatsLine()
            .snapshot()
            .customLine2();

        assertTrue(line.contains("CUSTOM EVENT LIVE"));
    }
}
