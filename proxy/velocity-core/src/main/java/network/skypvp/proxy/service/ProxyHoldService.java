package network.skypvp.proxy.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import java.util.UUID;

public interface ProxyHoldService extends AutoCloseable {
   boolean available();

   boolean shouldHoldLogin(Player var1);

   void holdLogin(Player var1);

   boolean holdForOutage(Player var1, String var2, String var3);

   boolean rerouteHeld(Player var1, String var2, RegisteredServer var3);

   boolean releaseHeld(UUID var1, RegisteredServer var2);

   boolean releaseHeld(Player var1, RegisteredServer var2);

   void onBackendConnected(Player var1);

   void onProxyDisconnect(UUID var1);

   @Override
   void close();
}
