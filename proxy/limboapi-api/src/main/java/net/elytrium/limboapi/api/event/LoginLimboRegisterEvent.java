package net.elytrium.limboapi.api.event;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

public class LoginLimboRegisterEvent {
   private final Player player;
   private final Queue<Runnable> onJoinCallbacks;
   private Function<KickedFromServerEvent, Boolean> onKickCallback;

   public LoginLimboRegisterEvent(Player player) {
      this.player = (Player)Preconditions.checkNotNull(player, "player");
      this.onJoinCallbacks = new LinkedBlockingQueue<>();
   }

   public Player getPlayer() {
      return this.player;
   }

   @Override
   public String toString() {
      return "LoginLimboRegisterEvent{player=" + this.player + "}";
   }

   public Queue<Runnable> getOnJoinCallbacks() {
      return this.onJoinCallbacks;
   }

   public Function<KickedFromServerEvent, Boolean> getOnKickCallback() {
      return this.onKickCallback;
   }

   public void addOnJoinCallback(Runnable callback) {
      this.onJoinCallbacks.add(callback);
   }

   @Deprecated
   public void addCallback(Runnable callback) {
      this.onJoinCallbacks.add(callback);
   }

   public void setOnKickCallback(Function<KickedFromServerEvent, Boolean> callback) {
      this.onKickCallback = callback;
   }
}
