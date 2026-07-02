package network.skypvp.shared.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Azure text moderation client. Uses Azure AI Content Safety first, then falls back to the
 * legacy Content Moderator API for older resources.
 */
public final class TextModerationClient {
    private static final String CONTENT_SAFETY_API_VERSION = "2024-09-01";

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final int contentSafetyMinSeverity;
    private final Logger logger;

    public TextModerationClient(String endpoint, String apiKey, Logger logger) {
        this(endpoint, apiKey, 2, logger);
    }

    public TextModerationClient(String endpoint, String apiKey, int contentSafetyMinSeverity, Logger logger) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.contentSafetyMinSeverity = Math.max(0, contentSafetyMinSeverity);
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean enabled() {
        return !this.endpoint.isBlank() && !this.apiKey.isBlank();
    }

    public TextModerationResult moderate(String text, String language) {
        if (!enabled() || text == null || text.isBlank()) {
            return TextModerationResult.clean();
        }

        TextModerationResult contentSafety = moderateContentSafety(text);
        if (contentSafety != null) {
            return contentSafety;
        }

        return moderateLegacyContentModerator(text, language);
    }

    private TextModerationResult moderateContentSafety(String text) {
        try {
            String url = this.endpoint + "/contentsafety/text:analyze?api-version=" + CONTENT_SAFETY_API_VERSION;
            JsonObject body = new JsonObject();
            body.addProperty("text", text);
            JsonArray categories = new JsonArray();
            categories.add("Hate");
            categories.add("Sexual");
            categories.add("SelfHarm");
            categories.add("Violence");
            body.add("categories", categories);
            body.addProperty("outputType", "FourSeverityLevels");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Ocp-Apim-Subscription-Key", this.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 410) {
                this.logger.info("[chat-moderation] Content Safety API unavailable (HTTP "
                        + response.statusCode() + "); trying legacy Content Moderator.");
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                this.logger.warning("[chat-moderation] Content Safety HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
                return null;
            }
            return parseContentSafetyResponse(response.body());
        } catch (Exception ex) {
            this.logger.warning("[chat-moderation] Content Safety failed: " + ex.getMessage());
            return null;
        }
    }

    private TextModerationResult parseContentSafetyResponse(String body) {
        if (body == null || body.isBlank()) {
            return TextModerationResult.clean();
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        boolean blocklistHit = root.has("blocklistsMatch")
                && root.get("blocklistsMatch").isJsonArray()
                && !root.getAsJsonArray("blocklistsMatch").isEmpty();

        int maxSeverity = 0;
        if (root.has("categoriesAnalysis") && root.get("categoriesAnalysis").isJsonArray()) {
            for (var element : root.getAsJsonArray("categoriesAnalysis")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject category = element.getAsJsonObject();
                if (category.has("severity")) {
                    maxSeverity = Math.max(maxSeverity, category.get("severity").getAsInt());
                }
            }
        }

        boolean flagged = blocklistHit || maxSeverity >= this.contentSafetyMinSeverity;
        double normalizedScore = maxSeverity <= 0 ? 0.0D : Math.min(1.0D, maxSeverity / 6.0D);
        return new TextModerationResult(flagged, 0.0D, 0.0D, normalizedScore, blocklistHit, body);
    }

    private TextModerationResult moderateLegacyContentModerator(String text, String language) {
        try {
            String lang = language == null || language.isBlank() ? "eng" : language;
            String url = this.endpoint
                    + "/contentmoderator/moderate/v1.0/ProcessText/Screen?classify=true&PII=false&language="
                    + lang;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "text/plain")
                    .header("Ocp-Apim-Subscription-Key", this.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                this.logger.warning("[chat-moderation] Legacy Content Moderator HTTP "
                        + response.statusCode() + ": " + truncate(response.body()));
                return TextModerationResult.clean();
            }
            return parseLegacyResponse(response.body());
        } catch (Exception ex) {
            this.logger.warning("[chat-moderation] Legacy Content Moderator failed: " + ex.getMessage());
            return TextModerationResult.clean();
        }
    }

    private TextModerationResult parseLegacyResponse(String body) {
        if (body == null || body.isBlank()) {
            return TextModerationResult.clean();
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        boolean reviewRecommended = root.has("ReviewRecommended") && root.get("ReviewRecommended").getAsBoolean();
        double category1 = score(root, "Category1");
        double category2 = score(root, "Category2");
        double category3 = score(root, "Category3");
        boolean profanity = root.has("Terms") && root.get("Terms").isJsonArray() && !root.getAsJsonArray("Terms").isEmpty();
        return new TextModerationResult(reviewRecommended, category1, category2, category3, profanity, body);
    }

    private static double score(JsonObject root, String category) {
        if (!root.has("Classification") || !root.get("Classification").isJsonObject()) {
            return 0.0;
        }
        JsonObject classification = root.getAsJsonObject("Classification");
        if (!classification.has(category) || !classification.get(category).isJsonObject()) {
            return 0.0;
        }
        JsonObject node = classification.getAsJsonObject(category);
        return node.has("Score") ? node.get("Score").getAsDouble() : 0.0;
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160) + "...";
    }
}
