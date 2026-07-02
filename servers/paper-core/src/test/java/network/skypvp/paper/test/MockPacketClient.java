package network.skypvp.paper.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.mockito.Mockito;
import java.util.ArrayList;
import java.util.List;

public class MockPacketClient {

    private final Player mockPlayer;
    private final List<Object> capturedPackets = new ArrayList<>();

    public MockPacketClient() {
        this.mockPlayer = Mockito.mock(Player.class);

        // Track "packets" translated from Bukkit API
        Mockito.doAnswer(invocation -> {
            Scoreboard board = invocation.getArgument(0);
            capturedPackets.add(new SetScoreboardPacketMock(board));
            return null;
        }).when(this.mockPlayer).setScoreboard(Mockito.any(Scoreboard.class));

        Mockito.doAnswer(invocation -> {
            Component header = invocation.getArgument(0);
            Component footer = invocation.getArgument(1);
            capturedPackets.add(new SetTabListPacketMock(header, footer));
            return null;
        }).when(this.mockPlayer).sendPlayerListHeaderAndFooter(Mockito.any(Component.class), Mockito.any(Component.class));

        Mockito.doAnswer(invocation -> {
            Component message = invocation.getArgument(0);
            capturedPackets.add(new SetActionBarPacketMock(message));
            return null;
        }).when(this.mockPlayer).sendActionBar(Mockito.any(Component.class));

        Mockito.doAnswer(invocation -> {
            BossBar bar = invocation.getArgument(0);
            capturedPackets.add(new ShowBossBarPacketMock(bar));
            return null;
        }).when(this.mockPlayer).showBossBar(Mockito.any(BossBar.class));

        Mockito.doAnswer(invocation -> {
            BossBar bar = invocation.getArgument(0);
            capturedPackets.add(new HideBossBarPacketMock(bar));
            return null;
        }).when(this.mockPlayer).hideBossBar(Mockito.any(BossBar.class));
        
        // Setup base stubs
        Mockito.when(this.mockPlayer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        Mockito.when(this.mockPlayer.getName()).thenReturn("TestPlayer");
        Mockito.when(this.mockPlayer.isOnline()).thenReturn(true);
    }

    public Player getMockPlayer() {
        return mockPlayer;
    }

    public List<Object> getCapturedPackets() {
        return capturedPackets;
    }

    public void clearPackets() {
        capturedPackets.clear();
    }

    public record SetScoreboardPacketMock(Scoreboard scoreboard) {}
    public record SetTabListPacketMock(Component header, Component footer) {}
    public record SetActionBarPacketMock(Component message) {}
    public record ShowBossBarPacketMock(BossBar bar) {}
    public record HideBossBarPacketMock(BossBar bar) {}
}
