package network.skypvp.paper.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Minimal HTTP server that exposes the network resource pack zip to joining clients.
 */
final class ResourcePackHttpServer implements AutoCloseable {

    private final Logger logger;
    private final Path packFile;
    private final String fileName;
    private HttpServer server;

    ResourcePackHttpServer(Logger logger, Path packFile, String fileName) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.packFile = Objects.requireNonNull(packFile, "packFile");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
    }

    void start(int port) throws IOException {
        if (server != null) {
            return;
        }
        if (!Files.isRegularFile(packFile)) {
            throw new IOException("Resource pack file missing: " + packFile);
        }

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/" + fileName, this::handlePack);
        http.createContext("/resource-pack/" + fileName, this::handlePack);
        http.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "skypvp-resource-pack-http");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();
        this.server = http;
        this.logger.info("[resource-pack] Serving " + fileName + " on port " + port);
    }

    private void handlePack(HttpExchange exchange) throws IOException {
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
}
