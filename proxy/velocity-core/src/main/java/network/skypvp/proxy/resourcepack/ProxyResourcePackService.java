package network.skypvp.proxy.resourcepack;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.proxy.config.ProxyBootstrapConfig;
import org.slf4j.Logger;

/**
 * Network-wide resource pack. When hosted on skypvp-web, SHA1/URL are refreshed from
 * {@code /api/pack} (or {@code /pack/*.meta.json}) so proxy redeploys are not required
 * after every pack sync.
 */
public final class ProxyResourcePackService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyBootstrapConfig.ResourcePackSettings settings;

    private ProxyResourcePackHttpServer httpServer;
    private Path packFile;
    private final AtomicReference<PackOffer> offer = new AtomicReference<>();
    private String configuredBaseUrl = "";
    private String metaUrl = "";
    private boolean active;

    private final Set<UUID> applied = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingSinceMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Continuation> configurationHolds = new ConcurrentHashMap<>();

    public ProxyResourcePackService(
            ProxyServer proxyServer,
            Logger logger,
            Path dataDirectory,
            ProxyBootstrapConfig.ResourcePackSettings settings
    ) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.settings = settings == null ? new ProxyBootstrapConfig.ResourcePackSettings() : settings;
    }

    public void start() {
        shutdown();
        if (!settings.enabled) {
            this.logger.info("[resource-pack] Disabled (proxy).");
            return;
        }

        try {
            String fileName = blankTo(settings.localFile, "skypvp-core.zip");
            this.packFile = ProxyResourcePackHttpServer.extractBundledPack(
                    dataDirectory,
                    fileName,
                    ProxyResourcePackService.class.getClassLoader(),
                    logger
            );

            this.configuredBaseUrl = stripQuery(blankTo(settings.url, ""));
            this.metaUrl = resolveMetaUrl(blankTo(settings.metaUrl, ""), this.configuredBaseUrl);

            String bootstrapSha1 = normalizeSha1(settings.sha1);
            if (bootstrapSha1.isBlank()) {
                bootstrapSha1 = ProxyResourcePackHttpServer.sha1Hex(packFile);
            }
            applyOffer(bootstrapSha1, this.configuredBaseUrl, "bootstrap");

            if (!this.metaUrl.isBlank()) {
                refreshFromMeta("startup");
            }

            if (settings.serveLocally) {
                this.httpServer = new ProxyResourcePackHttpServer(logger, packFile, fileName);
                this.httpServer.start(Math.max(1, settings.servePort));
            }

            this.active = offer.get() != null;
            PackOffer current = offer.get();
            this.logger.info(
                    "[resource-pack] Proxy pack ready sha1={} metaUrl={} servePort={} fixedUrl={}",
                    current == null ? "?" : current.sha1Hex(),
                    this.metaUrl.isBlank() ? "(none)" : this.metaUrl,
                    settings.servePort,
                    current == null || current.offerUrl().isBlank()
                            ? "(auto from publicHost)"
                            : current.offerUrl()
            );
        } catch (Exception ex) {
            this.active = false;
            closeHttp();
            this.logger.error("[resource-pack] Failed to start proxy pack host", ex);
        }
    }

    public void shutdown() {
        for (Continuation continuation : configurationHolds.values()) {
            try {
                continuation.resume();
            } catch (RuntimeException ignored) {
            }
        }
        configurationHolds.clear();
        applied.clear();
        pendingSinceMillis.clear();
        closeHttp();
        this.active = false;
        this.packFile = null;
        this.offer.set(null);
        this.configuredBaseUrl = "";
        this.metaUrl = "";
    }

    public boolean isActive() {
        PackOffer current = offer.get();
        return active && current != null && current.sha1Bytes().length == 20;
    }

    public boolean isForced() {
        return isActive() && settings.force;
    }

    public boolean usesRemoteMeta() {
        return !metaUrl.isBlank();
    }

    public long metaRefreshSeconds() {
        return Math.max(15L, settings.metaRefreshSeconds);
    }

    /** Periodic / on-demand refresh from skypvp-web pack metadata. */
    public void refreshFromMeta() {
        refreshFromMeta("scheduled");
    }

    public void holdConfiguration(Player player, Continuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        if (player == null || !isActive()) {
            continuation.resume();
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!settings.force) {
            offerPack(player);
            continuation.resume();
            return;
        }

        if (applied.contains(playerId)) {
            continuation.resume();
            return;
        }

        Continuation previous = configurationHolds.put(playerId, continuation);
        if (previous != null && previous != continuation) {
            try {
                previous.resume();
            } catch (RuntimeException ignored) {
            }
        }

        if (!offerPack(player)) {
            resumeConfiguration(playerId);
        }
    }

    public void handleStatus(Player player, UUID packId, PlayerResourcePackStatusEvent.Status status) {
        if (!isActive() || player == null || status == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        switch (status) {
            case SUCCESSFUL -> {
                pendingSinceMillis.remove(playerId);
                applied.add(playerId);
                this.logger.info("[resource-pack] {} successfully applied the pack", player.getUsername());
                resumeConfiguration(playerId);
            }
            case ACCEPTED, DOWNLOADED -> {
            }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED, INVALID_URL -> {
                pendingSinceMillis.remove(playerId);
                applied.remove(playerId);
                this.logger.info("[resource-pack] {} pack status {} — denying login", player.getUsername(), status);
                if (settings.force && status != PlayerResourcePackStatusEvent.Status.DECLINED && player.isActive()) {
                    player.disconnect(kickComponent());
                }
                resumeConfiguration(playerId);
            }
            default -> {
            }
        }
    }

    public void handleDisconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        applied.remove(playerId);
        pendingSinceMillis.remove(playerId);
        resumeConfiguration(playerId);
    }

    public void enforceTimeouts() {
        if (!isActive() || !settings.force) {
            return;
        }
        long timeoutMs = Math.max(5L, settings.applyTimeoutSeconds) * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : pendingSinceMillis.entrySet()) {
            if (now - entry.getValue() < timeoutMs) {
                continue;
            }
            UUID playerId = entry.getKey();
            pendingSinceMillis.remove(playerId);
            Optional<Player> player = proxyServer.getPlayer(playerId);
            player.ifPresent(p -> {
                this.logger.info("[resource-pack] Disconnecting {} for pack apply timeout", p.getUsername());
                p.disconnect(kickComponent());
            });
            resumeConfiguration(playerId);
        }
    }

    private void refreshFromMeta(String reason) {
        if (metaUrl.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(metaUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json, text/plain, */*")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                this.logger.warn("[resource-pack] Meta fetch {} returned HTTP {}", metaUrl, response.statusCode());
                return;
            }
            String body = stripBom(response.body() == null ? "" : response.body()).trim();
            String sha1 = "";
            String url = configuredBaseUrl;
            if (body.startsWith("{")) {
                sha1 = normalizeSha1(jsonString(body, "sha1"));
                String remoteUrl = jsonString(body, "url");
                if (!remoteUrl.isBlank()) {
                    url = stripQuery(remoteUrl);
                }
                String downloadUrl = jsonString(body, "downloadUrl");
                if (!downloadUrl.isBlank() && sha1.isBlank()) {
                    sha1 = normalizeSha1(queryParam(downloadUrl, "h"));
                }
            } else {
                sha1 = normalizeSha1(body);
            }
            if (sha1.isBlank()) {
                this.logger.warn("[resource-pack] Meta fetch {} missing sha1 ({})", metaUrl, reason);
                return;
            }
            if (url.isBlank()) {
                url = configuredBaseUrl;
            }
            applyOffer(sha1, url, reason);
        } catch (Exception ex) {
            this.logger.warn("[resource-pack] Meta fetch failed ({}): {}", reason, ex.getMessage());
        }
    }

    private void applyOffer(String sha1Hex, String baseUrl, String reason) {
        String normalized = normalizeSha1(sha1Hex);
        if (normalized.isBlank()) {
            return;
        }
        String base = stripQuery(blankTo(baseUrl, configuredBaseUrl));
        String offerUrl = base.isBlank() ? "" : withCacheBust(base, normalized);
        PackOffer next = new PackOffer(normalized, HexFormat.of().parseHex(normalized), offerUrl);
        PackOffer previous = offer.getAndSet(next);
        if (previous == null || !previous.sha1Hex().equals(normalized)) {
            this.logger.info(
                    "[resource-pack] Pack hash {} → {} ({})",
                    previous == null ? "(none)" : previous.sha1Hex(),
                    normalized,
                    reason
            );
            // New hash: players who already applied must re-accept on next offer/login.
            if (previous != null) {
                applied.clear();
            }
        }
        this.active = true;
    }

    private boolean offerPack(Player player) {
        if (!isActive() || player == null || !player.isActive()) {
            return false;
        }
        PackOffer current = offer.get();
        if (current == null) {
            return false;
        }

        String url = current.offerUrl();
        if (url == null || url.isBlank()) {
            url = resolveLocalUrl(player);
        }
        if (url == null || url.isBlank()) {
            this.logger.warn(
                    "[resource-pack] No reachable pack URL for {} — set SPVP_RESOURCE_PACK_PUBLIC_HOST or SPVP_RESOURCE_PACK_URL",
                    player.getUsername()
            );
            if (settings.force) {
                player.disconnect(parseComponent(
                        settings.kickMessage,
                        "<red>Resource pack host is misconfigured. Contact staff."
                ));
            }
            return false;
        }

        UUID packId = parseUuid(settings.uuid, ProxyResourcePackHttpServer.defaultPackId());
        ResourcePackInfo.Builder builder = proxyServer.createResourcePackBuilder(url)
                .setId(packId)
                .setHash(current.sha1Bytes())
                .setShouldForce(settings.force)
                .setPrompt(parseComponent(
                        settings.prompt,
                        "<gold>SkyPvP</gold> <gray>requires its resource pack for weapons, lasers, and effects."
                ));
        player.sendResourcePackOffer(builder.build());
        pendingSinceMillis.put(player.getUniqueId(), System.currentTimeMillis());
        this.logger.info("[resource-pack] Offered pack to {} via {}", player.getUsername(), url);
        return true;
    }

    private void resumeConfiguration(UUID playerId) {
        Continuation continuation = configurationHolds.remove(playerId);
        if (continuation == null) {
            return;
        }
        try {
            continuation.resume();
        } catch (RuntimeException ex) {
            this.logger.warn("[resource-pack] Failed to resume configuration for {}: {}", playerId, ex.getMessage());
        }
    }

    private Component kickComponent() {
        return parseComponent(
                settings.kickMessage,
                "<red>You must accept the SkyPvP resource pack to play."
        );
    }

    private String resolveLocalUrl(Player player) {
        String fileName = packFile.getFileName().toString();
        String publicHost = sanitizePublicHost(blankTo(settings.publicHost, ""));
        if (publicHost.isBlank()) {
            publicHost = sanitizePublicHost(blankTo(System.getenv("SPVP_RESOURCE_PACK_PUBLIC_HOST"), ""));
        }
        if (publicHost.isBlank()) {
            publicHost = sanitizePublicHost(player.getVirtualHost()
                    .map(InetSocketAddress::getHostString)
                    .orElse(""));
        }
        if (publicHost.isBlank() || isLoopback(publicHost) || looksLikeClusterIp(publicHost)) {
            return null;
        }
        int port = Math.max(1, settings.servePort);
        return "http://" + publicHost + ":" + port + "/" + fileName;
    }

    static String sanitizePublicHost(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String host = raw.trim();
        if (host.contains("${")) {
            return "";
        }
        int payload = host.indexOf("///");
        if (payload >= 0) {
            host = host.substring(0, payload);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        host = host.trim();
        if (host.isBlank() || host.length() > 253) {
            return "";
        }
        if (host.indexOf(' ') >= 0 || host.indexOf('=') >= 0 || host.indexOf('+') >= 0) {
            return "";
        }
        if (!host.matches("(?i)^(\\[[0-9a-f:]+]|[a-z0-9][a-z0-9.\\-]*[a-z0-9]|[a-z0-9]|\\d{1,3}(\\.\\d{1,3}){3})$")) {
            return "";
        }
        return host;
    }

    private static boolean isLoopback(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "0.0.0.0".equals(h);
    }

    private static boolean looksLikeClusterIp(String host) {
        String h = host.trim();
        return h.startsWith("10.42.") || h.startsWith("10.43.") || h.startsWith("10.244.");
    }

    private void closeHttp() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    private static Component parseComponent(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        return MINI.deserialize(value);
    }

    private static UUID parseUuid(String raw, UUID fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String stripBom(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;
    }

    private static String normalizeSha1(String raw) {
        if (raw == null) {
            return "";
        }
        String hex = stripBom(raw).trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (hex.startsWith("sha1:")) {
            hex = hex.substring(5);
        }
        return hex.matches("[0-9a-f]{40}") ? hex : "";
    }

    private static String blankTo(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static String stripQuery(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url.trim();
    }

    private static String withCacheBust(String baseUrl, String sha1) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "h=" + sha1;
    }

    private static String resolveMetaUrl(String configured, String packUrl) {
        if (!configured.isBlank()) {
            return configured;
        }
        if (packUrl.isBlank()) {
            return "";
        }
        String base = stripQuery(packUrl);
        if (base.endsWith(".zip")) {
            return base.substring(0, base.length() - 4) + ".meta.json";
        }
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            return base.substring(0, slash + 1) + "skypvp-core.meta.json";
        }
        return "";
    }

    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyIdx + needle.length());
        if (colon < 0) {
            return "";
        }
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return "";
        }
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) {
            return "";
        }
        return json.substring(startQuote + 1, endQuote);
    }

    private static String queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        String[] parts = url.substring(q + 1).split("&");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (part.substring(0, eq).equalsIgnoreCase(name)) {
                return part.substring(eq + 1);
            }
        }
        return "";
    }

    private record PackOffer(String sha1Hex, byte[] sha1Bytes, String offerUrl) {
    }
}
