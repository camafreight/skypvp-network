package network.skypvp.paper.integration;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiPlaceholderTexts;
import org.bukkit.entity.Player;

/**
 * Resolves {@code %skypvp_...%}, {@code %skypvpchat_...%}, and PlaceholderAPI tokens for NPC/hologram
 * interaction payloads (commands, messages, connect targets). Prefer registered PAPI expansions over
 * hardcoded replacements — {@link SkyPvPPlaceholderExpansion} covers network placeholders.
 */
public final class InteractionPlaceholderResolver {

    private InteractionPlaceholderResolver() {
    }

    public static String resolve(PaperCorePlugin plugin, Player player, String template) {
        return GuiPlaceholderTexts.resolveTemplate(plugin, player, template);
    }

    public static String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }
}
