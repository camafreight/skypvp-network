package network.skypvp.shared.chat;

import java.util.logging.Logger;

public final class ChatTranslatorFactory {

    public static final String PROVIDER_AZURE = "azure";

    private ChatTranslatorFactory() {
    }

    public static ChatTranslator createAzure(
            String azureEndpoint,
            String azureApiKey,
            String azureRegion,
            Logger logger
    ) {
        return new AzureTranslatorClient(azureEndpoint, azureApiKey, azureRegion, logger);
    }
}
