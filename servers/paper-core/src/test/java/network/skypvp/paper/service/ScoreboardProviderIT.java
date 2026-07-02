package network.skypvp.paper.service;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.test.MockPacketClient;
import network.skypvp.paper.test.PaperCoreTestPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import org.bukkit.Server;

import static org.junit.jupiter.api.Assertions.*;

public class ScoreboardProviderIT {

    private PaperCorePlugin mockPlugin;
    private HudProviderService mockHudProviderService;
    private GameModeBehaviorService mockBehaviorService;
    private ScoreboardService scoreboardService;
    private MockPacketClient client;

    private Server mockServer;

    @BeforeEach
    public void setupService() {
        mockServer = Mockito.mock(Server.class);
        mockPlugin = Mockito.mock(PaperCorePlugin.class);
        mockHudProviderService = Mockito.mock(HudProviderService.class);
        mockBehaviorService = Mockito.mock(GameModeBehaviorService.class);
        PerformanceMonitorService mockPerf = Mockito.mock(PerformanceMonitorService.class);

        Mockito.when(mockPlugin.getServer()).thenReturn(mockServer);
        Mockito.when(mockPlugin.hudProviderService()).thenReturn(mockHudProviderService);
        Mockito.when(mockPlugin.gameModeBehaviorService()).thenReturn(mockBehaviorService);
        Mockito.when(mockPlugin.performanceMonitorService()).thenReturn(mockPerf);

        Mockito.when(mockBehaviorService.booleanValue(Mockito.eq("core.hud.scoreboard.enabled"), Mockito.anyBoolean())).thenReturn(true);

        scoreboardService = new ScoreboardService(mockPlugin, null);
        client = new MockPacketClient();
    }

    @Test
    public void testHeadlessScoreboardDispatchesNoPackets() {
        try (org.mockito.MockedStatic<org.bukkit.Bukkit> bukkitMock = Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            org.bukkit.scoreboard.ScoreboardManager mockManager = Mockito.mock(org.bukkit.scoreboard.ScoreboardManager.class);
            org.bukkit.scoreboard.Scoreboard mainScoreboard = Mockito.mock(org.bukkit.scoreboard.Scoreboard.class);
            Mockito.when(mockManager.getMainScoreboard()).thenReturn(mainScoreboard);
            bukkitMock.when(org.bukkit.Bukkit::getScoreboardManager).thenReturn(mockManager);

            Mockito.when(mockHudProviderService.activeProvider()).thenReturn(Optional.empty());

            scoreboardService.setupPlayer(client.getMockPlayer());
            
            List<Object> packets = client.getCapturedPackets();
            boolean hasSetScoreboard = packets.stream().anyMatch(p -> p instanceof MockPacketClient.SetScoreboardPacketMock);
            
            assertTrue(hasSetScoreboard);
            MockPacketClient.SetScoreboardPacketMock lastPacket = (MockPacketClient.SetScoreboardPacketMock) packets.get(packets.size() - 1);
            assertEquals(mainScoreboard, lastPacket.scoreboard(), "Headless scoreboard should fallback to main scoreboard");
        }
    }
}
