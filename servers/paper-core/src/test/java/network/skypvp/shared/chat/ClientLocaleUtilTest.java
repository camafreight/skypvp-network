package network.skypvp.shared.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientLocaleUtilTest {

    @Test
    void mapsCommonMinecraftLocalesToAzure() {
        assertEquals("en", ClientLocaleUtil.translationFamily("en_us"));
        assertEquals("en-US", ClientLocaleUtil.toAzureLanguage("en_us"));
        assertEquals("en-GB", ClientLocaleUtil.toAzureLanguage("en_gb"));

        assertEquals("zh-Hans", ClientLocaleUtil.toAzureLanguage("zh_cn"));
        assertEquals("zh-Hant", ClientLocaleUtil.toAzureLanguage("zh_tw"));
        assertEquals("zh-Hant", ClientLocaleUtil.toAzureLanguage("zh_hk"));

        assertEquals("pt", ClientLocaleUtil.toAzureLanguage("pt_br"));
        assertEquals("pt-pt", ClientLocaleUtil.toAzureLanguage("pt_pt"));

        assertEquals("nb", ClientLocaleUtil.toAzureLanguage("no_no"));
        assertEquals("fil", ClientLocaleUtil.toAzureLanguage("fil_ph"));

        assertEquals("ja", ClientLocaleUtil.toAzureLanguage("ja_jp"));
        assertEquals("ko", ClientLocaleUtil.toAzureLanguage("ko_kr"));
        assertEquals("es-MX", ClientLocaleUtil.toAzureLanguage("es_mx"));
    }

    @Test
    void normalizesJavaLocaleStrings() {
        assertEquals("en_us", ClientLocaleUtil.normalizeMinecraftLocale("en_US"));
        assertEquals("en_us", ClientLocaleUtil.normalizeMinecraftLocale("en-US"));
        assertEquals("en_us", ClientLocaleUtil.normalizeMinecraftLocale("en"));
    }

    @Test
    void sameLanguageUsesFamilyNotRegion() {
        assertTrue(ClientLocaleUtil.sameLanguage("en_us", "en_gb"));
        assertTrue(ClientLocaleUtil.sameLanguage("en_us", "en_au"));
        assertTrue(ClientLocaleUtil.sameLanguage("es_es", "es_mx"));

        assertFalse(ClientLocaleUtil.sameLanguage("en_us", "es_es"));
        assertFalse(ClientLocaleUtil.sameLanguage("zh_cn", "zh_tw"));
        assertFalse(ClientLocaleUtil.sameLanguage("pt_br", "pt_pt"));
    }
}
