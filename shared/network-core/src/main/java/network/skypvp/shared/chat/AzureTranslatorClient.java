package network.skypvp.shared.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Azure Translator Text API v3.0 client.
 */
public final class AzureTranslatorClient implements ChatTranslator {
    private static final String API_VERSION = "3.0";
    private static final int MAX_CACHE_ENTRIES = 2048;

    private final HttpClient httpClient;
    private final String translateUrl;
    private final String apiKey;
    private final String region;
    private final Logger logger;
    private final Map<String, String> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public AzureTranslatorClient(String endpoint, String apiKey, String region, Logger logger) {
        this.translateUrl = buildTranslateUrl(ChatTranslationDiagnostics.sanitizeEnv(endpoint));
        this.apiKey = ChatTranslationDiagnostics.sanitizeEnv(apiKey);
        this.region = ChatTranslationDiagnostics.sanitizeEnv(region);
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String providerId() {
        return ChatTranslatorFactory.PROVIDER_AZURE;
    }

    @Override
    public boolean enabled() {
        return !this.translateUrl.isBlank() && !this.apiKey.isBlank();
    }

    @Override
    public String configuredSummary() {
        return "endpoint=" + this.translateUrl
                + " region="
                + (this.region.isBlank() ? "(none)" : this.region);
    }

    @Override
    public String translate(String text, String fromLocale, String toLocale) {
        if (!enabled() || text == null || text.isBlank()) {
            return text;
        }
        String from = ClientLocaleUtil.toAzureLanguage(fromLocale);
        String to = ClientLocaleUtil.toAzureLanguage(toLocale);
        if (from.equalsIgnoreCase(to)) {
            return text;
        }

        String cacheKey = from + "|" + to + "|" + text;
        synchronized (this.cache) {
            String cached = this.cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        String translated = requestTranslation(text, from, to);
        if (translated == null || translated.isBlank()) {
            return text;
        }
        synchronized (this.cache) {
            this.cache.put(cacheKey, translated);
        }
        return translated;
    }

    private String requestTranslation(String text, String from, String to) {
        try {
            String query = "api-version=" + API_VERSION
                    + "&from=" + encode(from)
                    + "&to=" + encode(to);
            JsonArray body = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("Text", text);
            body.add(item);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(this.translateUrl + "?" + query))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Ocp-Apim-Subscription-Key", this.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (!this.region.isBlank()) {
                builder.header("Ocp-Apim-Subscription-Region", this.region);
            }

            HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                ChatTranslationDiagnostics.logApiFailure(this.logger, providerId(), response.statusCode(), response.body());
                return text;
            }
            String parsed = parseTranslation(response.body());
            if (parsed == null || parsed.isBlank()) {
                ChatTranslationDiagnostics.logApiFailure(
                        this.logger,
                        providerId(),
                        response.statusCode(),
                        "empty translation body"
                );
                return text;
            }
            return parsed;
        } catch (Exception ex) {
            this.logger.warning("[chat-translation] Request failed (" + from + " -> " + to + "): " + ex.getMessage());
            return text;
        }
    }

    public String configuredEndpoint() {
        return this.translateUrl;
    }

    public String configuredRegion() {
        return this.region;
    }

    private static String parseTranslation(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        JsonArray root = JsonParser.parseString(body).getAsJsonArray();
        if (root.isEmpty() || !root.get(0).isJsonObject()) {
            return "";
        }
        JsonObject first = root.get(0).getAsJsonObject();
        if (!first.has("translations") || !first.get("translations").isJsonArray()) {
            return "";
        }
        JsonArray translations = first.getAsJsonArray("translations");
        if (translations.isEmpty() || !translations.get(0).isJsonObject()) {
            return "";
        }
        JsonObject translation = translations.get(0).getAsJsonObject();
        return translation.has("text") ? translation.get("text").getAsString() : "";
    }

    private static String buildTranslateUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains("/translator/")) {
            return trimmed;
        }
        if (trimmed.endsWith("/translate")) {
            return trimmed;
        }
        if (trimmed.contains("cognitiveservices.azure.com")) {
            return trimmed + "/translator/text/v3.0/translate";
        }
        return trimmed + "/translate";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
