package network.skypvp.extraction.hud;

import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.clientupdate.ClientUpdatePipeline;
import org.bukkit.entity.Player;

/** Routes Adventure titles through {@link ClientUpdatePipeline} (coalesce + affinity). */
public final class ClientTitles {

    private ClientTitles() {
    }

    public static void offer(
            PaperCorePlugin core,
            Player player,
            Component title,
            Component subtitle,
            int fadeInTicks,
            int stayTicks,
            int fadeOutTicks
    ) {
        offer(core, player, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks, ClientUpdatePipeline.PRIORITY_NORMAL);
    }

    public static void offer(
            PaperCorePlugin core,
            Player player,
            Component title,
            Component subtitle,
            int fadeInTicks,
            int stayTicks,
            int fadeOutTicks,
            int priority
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (core != null && core.clientUpdatePipeline() != null) {
            core.clientUpdatePipeline().offerTitle(
                    player,
                    title == null ? Component.empty() : title,
                    subtitle == null ? Component.empty() : subtitle,
                    fadeInTicks,
                    stayTicks,
                    fadeOutTicks,
                    priority
            );
            return;
        }
        player.showTitle(net.kyori.adventure.title.Title.title(
                title == null ? Component.empty() : title,
                subtitle == null ? Component.empty() : subtitle,
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(Math.max(0, fadeInTicks) * 50L),
                        java.time.Duration.ofMillis(Math.max(0, stayTicks) * 50L),
                        java.time.Duration.ofMillis(Math.max(0, fadeOutTicks) * 50L)
                )
        ));
    }
}
