package network.skypvp.paper.library;

import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public enum NetworkSoundCue {
   UI_BUTTON_CLICK(Sound.UI_BUTTON_CLICK, 0.7F, 1.2F),
   UI_BUTTON_FAILURE(Sound.BLOCK_NOTE_BLOCK_BASS, 0.75F, 0.7F),
   UI_BUTTON_SUCCESS(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7F, 1.15F),
   UI_MENU_BACK(Sound.ITEM_BOOK_PAGE_TURN, 0.75F, 0.95F),
   UI_PAGE_TURN(Sound.ITEM_BOOK_PAGE_TURN, 0.65F, 1.1F),
   REWARD_COMPLETE(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.85F, 1.0F),
   NPC_INTERACT(Sound.UI_BUTTON_CLICK, 0.7F, 1.2F),
   HOLOGRAM_INTERACT(Sound.UI_BUTTON_CLICK, 0.7F, 1.25F);

   private final Sound sound;
   private final float volume;
   private final float pitch;

   private NetworkSoundCue(Sound sound, float volume, float pitch) {
      this.sound = sound;
      this.volume = volume;
      this.pitch = pitch;
   }

   public void play(Player player) {
      if (player != null) {
         player.playSound(player.getLocation(), this.sound, this.volume, this.pitch);
      }
   }

   public static NetworkSoundCue forNoticeTone(ServerTextUtil.ThemeTone tone) {
      if (tone == null) {
         return UI_BUTTON_CLICK;
      } else {
         return switch (tone) {
            case ALERT_GOLD, ALERT_YELLOW -> UI_BUTTON_FAILURE;
            case SUCCESS_GREEN -> UI_BUTTON_SUCCESS;
            default -> UI_BUTTON_CLICK;
         };
      }
   }
}
