package network.skypvp.proxy.util;

import com.velocitypowered.api.util.Favicon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import javax.imageio.ImageIO;

/**
 * Loads the SkyPvP multiplayer server-list icon (64×64) from bundled resources.
 * Source artwork: {@code skypvp-web/assets/skypvp-server-mini-logo.png}.
 */
public final class NetworkServerIconLibrary {

    private static final String RESOURCE_PATH = "/network/server-icon.png";
    private static final int ICON_SIZE = 64;

    private static volatile Optional<Favicon> cached = Optional.empty();
    private static volatile boolean loadAttempted;

    private NetworkServerIconLibrary() {
    }

    public static Optional<Favicon> favicon(Logger logger) {
        if (!loadAttempted) {
            synchronized (NetworkServerIconLibrary.class) {
                if (!loadAttempted) {
                    cached = load(logger);
                    loadAttempted = true;
                }
            }
        }
        return cached;
    }

    private static Optional<Favicon> load(Logger logger) {
        try (InputStream input = NetworkServerIconLibrary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                logger.warn("[MOTD] Missing bundled server icon at {}", RESOURCE_PATH);
                return Optional.empty();
            }
            BufferedImage source = ImageIO.read(input);
            if (source == null) {
                logger.warn("[MOTD] Could not decode bundled server icon");
                return Optional.empty();
            }
            BufferedImage icon = toSquareIcon(source);
            return Optional.of(Favicon.create(icon));
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("[MOTD] Failed to load server icon: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private static BufferedImage toSquareIcon(BufferedImage source) {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        graphics.dispose();
        return icon;
    }
}
