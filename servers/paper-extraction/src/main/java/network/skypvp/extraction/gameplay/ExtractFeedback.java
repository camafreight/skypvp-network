package network.skypvp.extraction.gameplay;

import net.kyori.adventure.text.Component;
import network.skypvp.extraction.hud.BreachCountdownTitle;
import network.skypvp.extraction.hud.ClientTitles;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.clientupdate.ClientUpdatePipeline;
import network.skypvp.paper.platform.ServerPlatform;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Titles + SFX for extract-zone enter / dwell countdown / cancel, and zone-close alerts.
 * Titles go through {@link ClientTitles}; sounds play on the player's region thread (same pattern as shield FX).
 */
public final class ExtractFeedback {

    private ExtractFeedback() {
    }

    public static void entered(PaperCorePlugin core, ServerPlatform scheduler, Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Component title = ExtractionTexts.miniMessage(player, "extraction.title.extract_started");
        Component subtitle = ExtractionTexts.miniMessage(player, "extraction.title.extract_started_subtitle");
        ClientTitles.offer(core, player, title, subtitle, 0, 30, 8, ClientUpdatePipeline.PRIORITY_FLASH);
        play(player, Sound.BLOCK_BEACON_ACTIVATE, 0.55F, 1.55F);
        play(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7F, 1.35F);
    }

    /**
     * Big digit slides in from the right once per remaining-second boundary while the player is extracting.
     */
    public static void countdownSecond(
            PaperCorePlugin core,
            ServerPlatform scheduler,
            Player player,
            int remainingSeconds
    ) {
        if (player == null || !player.isOnline() || remainingSeconds < 0) {
            return;
        }
        Component subtitle = ExtractionTexts.miniMessage(player, "extraction.title.extract_countdown_subtitle");
        int frames = BreachCountdownTitle.frameCount();
        for (int frame = 0; frame < frames; frame++) {
            int frameIndex = frame;
            Runnable show = () -> ClientTitles.offer(
                    core,
                    player,
                    BreachCountdownTitle.frameFromRight(remainingSeconds, frameIndex),
                    subtitle,
                    0,
                    18,
                    4,
                    ClientUpdatePipeline.PRIORITY_FLASH
            );
            if (frame == 0) {
                show.run();
            } else if (scheduler != null) {
                scheduler.runOnPlayerLater(player, show, frame);
            }
        }
        float pitch = Math.min(2.0F, 0.85F + (Math.max(0, 10 - remainingSeconds) * 0.08F));
        play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.45F, pitch);
        if (remainingSeconds <= 3) {
            play(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.35F, 1.2F + (3 - remainingSeconds) * 0.15F);
        }
    }

    public static void cancelled(PaperCorePlugin core, Player player, String reasonCatalogKey) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Component title = ExtractionTexts.miniMessage(player, "extraction.title.extract_cancelled");
        Component subtitle = reasonCatalogKey == null || reasonCatalogKey.isBlank()
                ? ExtractionTexts.miniMessage(player, "extraction.title.extract_cancelled_subtitle")
                : ExtractionTexts.miniMessage(player, reasonCatalogKey);
        ClientTitles.offer(core, player, title, subtitle, 0, 35, 10, ClientUpdatePipeline.PRIORITY_FLASH);
        play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.85F, 0.7F);
        play(player, Sound.ENTITY_ITEM_BREAK, 0.55F, 0.85F);
    }

    public static void leftZone(PaperCorePlugin core, Player player) {
        cancelled(core, player, "extraction.title.extract_cancelled_left_zone");
    }

    public static void zoneClosed(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        play(player, Sound.BLOCK_IRON_DOOR_CLOSE, 0.9F, 0.75F);
        play(player, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65F, 0.9F);
        play(player, Sound.BLOCK_BELL_USE, 0.35F, 0.6F);
    }

    public static void zoneClosingSoon(Player player, int secondsRemaining) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (secondsRemaining <= 5) {
            play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.55F, 1.6F);
            play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.4F, 0.8F);
            return;
        }
        play(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.45F, 1.05F);
    }

    public static void allZonesForceClosed(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        play(player, Sound.BLOCK_END_PORTAL_SPAWN, 0.45F, 0.85F);
        play(player, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.4F, 1.1F);
        play(player, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0F, 0.55F);
    }

    private static void play(Player player, Sound sound, float volume, float pitch) {
        // Respect the pipeline's per-tick SOUND budget so alert bursts (zone close hitting many
        // players at once) coalesce with the rest of the client-update traffic instead of
        // stacking unbounded packets on top of it.
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("SkyPvPCore")
                instanceof PaperCorePlugin core
                && core.clientUpdatePipeline() != null
                && !core.clientUpdatePipeline().tryAcquire(
                        network.skypvp.paper.clientupdate.UpdateChannel.SOUND, 1)) {
            return;
        }
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
