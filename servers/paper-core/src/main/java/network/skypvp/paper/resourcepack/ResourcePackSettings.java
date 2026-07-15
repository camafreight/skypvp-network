package network.skypvp.paper.resourcepack;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Network-wide forced resource pack settings loaded from {@code config.yml}.
 */
public record ResourcePackSettings(
        boolean enabled,
        boolean force,
        String url,
        String sha1Hex,
        UUID packId,
        Component prompt,
        Component kickMessage,
        boolean serveLocally,
        int servePort,
        String localFileName,
        long applyTimeoutSeconds
) {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final UUID DEFAULT_PACK_ID =
            UUID.fromString("a7c3e9f1-5b2d-4e8a-9c1f-0d6b4a8e2f35");

    public static ResourcePackSettings fromConfig(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("resource-pack");
        if (section == null) {
            return disabled();
        }

        String packIdRaw = section.getString("uuid", DEFAULT_PACK_ID.toString());
        UUID packId;
        try {
            packId = UUID.fromString(packIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            packId = DEFAULT_PACK_ID;
        }

        String sha1 = normalizeSha1(section.getString("sha1", ""));
        String url = blankToEmpty(section.getString("url", ""));
        boolean enabled = section.getBoolean("enabled", false);

        return new ResourcePackSettings(
                enabled,
                section.getBoolean("force", true),
                url,
                sha1,
                packId,
                parseComponent(
                        section.getString("prompt"),
                        "<gold>SkyPvP</gold> <gray>requires its resource pack for weapons, HUD, and effects."
                ),
                parseComponent(
                        section.getString("kick-message"),
                        "<red>You must accept the SkyPvP resource pack to play."
                ),
                section.getBoolean("serve-locally", true),
                Math.max(1, section.getInt("serve-port", 8765)),
                blankToEmpty(section.getString("local-file", "skypvp-core.zip")),
                Math.max(5L, section.getLong("apply-timeout-seconds", 45L))
        );
    }

    public static ResourcePackSettings disabled() {
        return new ResourcePackSettings(
                false,
                true,
                "",
                "",
                DEFAULT_PACK_ID,
                Component.empty(),
                Component.empty(),
                false,
                8765,
                "skypvp-core.zip",
                45L
        );
    }

    public ResourcePackSettings withUrlAndHash(String resolvedUrl, String resolvedSha1) {
        return new ResourcePackSettings(
                enabled,
                force,
                blankToEmpty(resolvedUrl),
                normalizeSha1(resolvedSha1),
                packId,
                prompt,
                kickMessage,
                serveLocally,
                servePort,
                localFileName,
                applyTimeoutSeconds
        );
    }

    public boolean hasSendablePack() {
        return enabled && !url.isBlank() && sha1Hex.length() == 40;
    }

    private static Component parseComponent(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        return MINI.deserialize(value);
    }

    private static String normalizeSha1(String raw) {
        if (raw == null) {
            return "";
        }
        String hex = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (hex.startsWith("sha1:")) {
            hex = hex.substring(5);
        }
        return hex.matches("[0-9a-f]{40}") ? hex : "";
    }

    private static String blankToEmpty(String raw) {
        return raw == null || raw.isBlank() ? "" : raw.trim();
    }
}
