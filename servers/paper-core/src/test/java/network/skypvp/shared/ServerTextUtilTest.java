package network.skypvp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
