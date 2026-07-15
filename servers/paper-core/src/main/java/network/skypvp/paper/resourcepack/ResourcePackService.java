package network.skypvp.paper.resourcepack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

/**
 * Network-wide forced resource pack delivery. Replaces WeaponMechanics pack handling.
 */
public final class ResourcePackService {

    private final PaperCorePlugin plugin;
    private final Map<UUID, Long> pendingSinceMillis = new ConcurrentHashMap<>();
    private ResourcePackSettings settings;
    private ResourcePackInfo packInfo;
    private ResourcePackHttpServer httpServer;
    private PlatformTask timeoutTask;

    public ResourcePackService(PaperCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = ResourcePackSettings.disabled();
    }

    public void start() {
        shutdown();
        ResourcePackSettings loaded = ResourcePackSettings.fromConfig(plugin.getConfig());
        if (!loaded.enabled()) {
            this.settings = loaded;
            this.plugin.getLogger().info("[resource-pack] Disabled in config.yml.");
            return;
        }

        try {
            Path packFile = resolvePackFile(loaded.localFileName());
            String sha1 = loaded.sha1Hex();
            if (sha1.isBlank()) {
                sha1 = sha1Hex(packFile);
            }

            String url = loaded.url();
            if (url.isBlank() || looksUnresolved(url)) {
                if (!loaded.serveLocally()) {
                    throw new IllegalStateException(
                            "resource-pack.url is empty and serve-locally is false; cannot send pack."
                    );
                }
                this.httpServer = new ResourcePackHttpServer(
                        plugin.getLogger(),
                        packFile,
                        packFile.getFileName().toString()
                );
                this.httpServer.start(loaded.servePort());
                url = buildLocalUrl(loaded.servePort(), packFile.getFileName().toString());
            } else if (loaded.serveLocally()) {
                this.httpServer = new ResourcePackHttpServer(
                        plugin.getLogger(),
                        packFile,
                        packFile.getFileName().toString()
                );
                this.httpServer.start(loaded.servePort());
            }

            this.settings = loaded.withUrlAndHash(url, sha1);
            this.packInfo = ResourcePackInfo.resourcePackInfo()
                    .id(this.settings.packId())
                    .uri(URI.create(this.settings.url()))
                    .hash(this.settings.sha1Hex())
                    .build();

            this.plugin.getLogger().info(
                    "[resource-pack] Forced pack ready id=" + this.settings.packId()
                            + " sha1=" + this.settings.sha1Hex()
                            + " url=" + this.settings.url()
            );

            this.timeoutTask = this.plugin.platform().runGlobalTimer(this::enforcePendingTimeouts, 20L, 20L);
            this.plugin.getLogger().info(
                    "[resource-pack] Apply timeout=" + this.settings.applyTimeoutSeconds() + "s."
            );
        } catch (Exception ex) {
            this.settings = ResourcePackSettings.disabled();
            this.packInfo = null;
            this.plugin.getLogger().log(
                    Level.SEVERE,
                    "[resource-pack] Failed to start forced resource pack; players will not be gated.",
                    ex
            );
            closeHttp();
        }
    }

    public void shutdown() {
        pendingSinceMillis.clear();
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        closeHttp();
        this.packInfo = null;
    }

    public ResourcePackSettings settings() {
        return settings;
    }

    public boolean isActive() {
        return settings.hasSendablePack() && packInfo != null;
    }

    public void sendTo(Player player) {
        if (!isActive() || player == null || !player.isOnline()) {
            return;
        }

        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(packInfo)
                .prompt(settings.prompt())
                .required(settings.force())
                .replace(true)
                .build();
        player.sendResourcePacks(request);
        pendingSinceMillis.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void handleStatus(Player player, UUID packId, Status status) {
        if (!isActive() || player == null || status == null) {
            return;
        }
        if (packId != null && !packId.equals(settings.packId())) {
            return;
        }

        switch (status) {
            case SUCCESSFULLY_LOADED -> pendingSinceMillis.remove(player.getUniqueId());
            case ACCEPTED, DOWNLOADED -> {
                // still pending until SUCCESSFULLY_LOADED
            }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED, INVALID_URL -> {
                pendingSinceMillis.remove(player.getUniqueId());
                if (settings.force()) {
                    kickForPack(player, status);
                }
            }
            default -> {
                // ignore unknown future statuses
            }
        }
    }

    public void handleQuit(UUID playerId) {
        if (playerId != null) {
            pendingSinceMillis.remove(playerId);
        }
    }

    private void enforcePendingTimeouts() {
        if (!isActive() || !settings.force()) {
            return;
        }
        long timeoutMs = settings.applyTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : pendingSinceMillis.entrySet()) {
            if (now - entry.getValue() < timeoutMs) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(entry.getKey());
            pendingSinceMillis.remove(entry.getKey());
            if (player != null && player.isOnline()) {
                kickForPack(player, Status.FAILED_DOWNLOAD);
            }
        }
    }

    private void kickForPack(Player player, Status status) {
        Component reason = settings.kickMessage();
        if (reason == null) {
            reason = Component.text("You must accept the SkyPvP resource pack to play.");
        }
        plugin.getLogger().info(
                "[resource-pack] Kicking " + player.getName() + " for pack status " + status
        );
        player.kick(reason);
    }

    private Path resolvePackFile(String fileName) throws IOException {
        Path dataPack = plugin.getDataFolder().toPath().resolve("resource-pack").resolve(fileName);
        Files.createDirectories(dataPack.getParent());
        if (Files.isRegularFile(dataPack)) {
            return dataPack;
        }

        String classpath = "resource-pack/" + fileName;
        try (InputStream in = plugin.getResource(classpath)) {
            if (in == null) {
                throw new IOException(
                        "Missing resource pack at " + dataPack + " and classpath " + classpath
                );
            }
            Files.copy(in, dataPack, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("[resource-pack] Extracted bundled pack to " + dataPack);
            return dataPack;
        }
    }

    private String buildLocalUrl(int port, String fileName) {
        String host = plugin.getConfig().getString("resource-pack.public-host", "");
        if (host == null || host.isBlank() || looksUnresolved(host)) {
            host = System.getenv("SPVP_ADVERTISED_HOST");
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
            plugin.getLogger().warning(
                    "[resource-pack] public-host / SPVP_ADVERTISED_HOST unset; using 127.0.0.1 (clients must reach this host)."
            );
        }
        return "http://" + host.trim() + ":" + port + "/" + fileName;
    }

    private static boolean looksUnresolved(String value) {
        return value != null && value.contains("${");
    }

    private static String sha1Hex(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private void closeHttp() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }
}
