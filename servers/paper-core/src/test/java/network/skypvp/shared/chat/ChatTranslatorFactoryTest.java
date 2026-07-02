package network.skypvp.shared.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatTranslatorFactoryTest {
    @Test
    void createAzureBuildsAzureClient() {
        ChatTranslator translator = ChatTranslatorFactory.createAzure(
                "https://api.cognitive.microsofttranslator.com",
                "test-key",
                "eastus",
                java.util.logging.Logger.getLogger("test")
        );
        assertEquals("azure", translator.providerId());
        assertTrue(translator.enabled());
    }

    @Test
    void createAzureWithoutCredentialsIsDisabled() {
        ChatTranslator translator = ChatTranslatorFactory.createAzure(
                "",
                "",
                "",
                java.util.logging.Logger.getLogger("test")
        );
        assertEquals("azure", translator.providerId());
        assertFalse(translator.enabled());
    }
}
