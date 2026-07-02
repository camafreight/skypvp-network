package network.skypvp.extraction.gameplay;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.service.ActionBarService;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class BreachCombatFeedback {

    private static final int DAMAGE_FEEDBACK_TICKS = 16;

    private BreachCombatFeedback() {
    }

    public static void showDamageTaken(Player victim, double damage, PaperCorePlugin core) {
        if (victim == null || damage <= 0.0D) {
            return;
        }
        int hearts = Math.max(1, (int) Math.ceil(damage / 2.0D));
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.65F, 1.0F);
        Component actionBar = ExtractionTexts.miniMessage(
                victim,
                "extraction.combat.feedback.damage_taken",
                hearts
        );
        showTemporaryActionBar(victim, core, actionBar);
    }

    public static void showEliminated(Player victim, String killerName, PaperCorePlugin core) {
        Objects.requireNonNull(victim, "victim");
        victim.playSound(victim.getLocation(), Sound.ENTITY_WITHER_HURT, 0.55F, 1.15F);
        Component title = ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_title");
        Component subtitle = killerName == null || killerName.isBlank()
                ? ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_subtitle_solo")
                : ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_subtitle_killer", killerName);
        victim.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))
        ));
        showTemporaryActionBar(
                victim,
                core,
                ExtractionTexts.miniMessage(victim, "extraction.combat.feedback.death_actionbar")
        );
    }

    private static void showTemporaryActionBar(Player player, PaperCorePlugin core, Component content) {
        if (core == null || core.actionBarService() == null || content == null) {
            return;
        }
        core.actionBarService().showTemporary(player.getUniqueId(), content, DAMAGE_FEEDBACK_TICKS);
    }
}
