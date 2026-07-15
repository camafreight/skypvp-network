package network.skypvp.proxy.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * ItemAdder-style self-host: serves the pack zip over HTTP from the proxy so clients can reach it
 * via the public LoadBalancer (Minecraft protocol still requires a download URL).
 */
final class ProxyResourcePackHttpServer implements AutoCloseable {

    private final Logger logger;
    private final Path packFile;
    private final String fileName;
    private HttpServer server;

    ProxyResourcePackHttpServer(Logger logger, Path packFile, String fileName) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.packFile = Objects.requireNonNull(packFile, "packFile");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
    }

    void start(int port) throws IOException {
        if (server != null) {
            return;
        }
        HttpServer http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        http.createContext("/" + fileName, this::handle);
        http.createContext("/resource-pack/" + fileName, this::handle);
        http.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "skypvp-proxy-resource-pack-http");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();
        this.server = http;
        this.logger.info("[resource-pack] Serving {} on 0.0.0.0:{}", fileName, port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        long size = Files.size(packFile);
        exchange.getResponseHeaders().add("Content-Type", "application/zip");
        exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, size);
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(packFile, out);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            this.logger.info("[resource-pack] Local pack HTTP server stopped.");
        }
    }

    static Path extractBundledPack(Path dataDirectory, String fileName, ClassLoader loader, Logger logger)
            throws IOException {
        Path dir = dataDirectory.resolve("resource-pack");
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        if (Files.isRegularFile(target)) {
            return target;
        }
        String classpath = "resource-pack/" + fileName;
        try (InputStream in = loader.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("Missing bundled resource pack: " + classpath);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            logger.info("[resource-pack] Extracted bundled pack to {}", target);
            return target;
        }
    }

    static String sha1Hex(Path file) throws Exception {
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

    static UUID defaultPackId() {
        return UUID.fromString("a7c3e9f1-5b2d-4e8a-9c1f-0d6b4a8e2f35");
    }
}
