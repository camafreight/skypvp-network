package network.skypvp.paper.event;

import network.skypvp.shared.chat.ClientLocaleUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Fired when a player changes their Minecraft client language (Client Settings packet). */
public final class PlayerClientLocaleChangeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String previousLocale;
    private final String newLocale;

    public PlayerClientLocaleChangeEvent(Player player, String previousLocale, String newLocale) {
        super(player);
        this.previousLocale = ClientLocaleUtil.normalizeMinecraftLocale(previousLocale);
        this.newLocale = ClientLocaleUtil.normalizeMinecraftLocale(newLocale);
    }

    public String previousLocale() {
        return this.previousLocale;
    }

    public String newLocale() {
        return this.newLocale;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
