package network.skypvp.paper.service;

import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.test.MockPacketClient;
import network.skypvp.paper.test.PaperCoreTestPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import org.bukkit.Server;

import static org.junit.jupiter.api.Assertions.*;

public class TabListProviderIT {

    private PaperCorePlugin mockPlugin;
    private HudProviderService mockHudProviderService;
    private GameModeBehaviorService mockBehaviorService;
    private TabListService tabListService;
    private MockPacketClient client;

    private Server mockServer;

    @BeforeEach
    public void setupService() {
        mockServer = Mockito.mock(Server.class);
        mockPlugin = Mockito.mock(PaperCorePlugin.class);
        mockHudProviderService = Mockito.mock(HudProviderService.class);
        mockBehaviorService = Mockito.mock(GameModeBehaviorService.class);
        PerformanceMonitorService mockPerf = Mockito.mock(PerformanceMonitorService.class);
        RankService mockRankService = Mockito.mock(RankService.class);

        Mockito.when(mockPlugin.getServer()).thenReturn(mockServer);
        Mockito.when(mockPlugin.hudProviderService()).thenReturn(mockHudProviderService);
        Mockito.when(mockPlugin.gameModeBehaviorService()).thenReturn(mockBehaviorService);
        Mockito.when(mockPlugin.performanceMonitorService()).thenReturn(mockPerf);

        Mockito.when(mockBehaviorService.booleanValue(Mockito.eq("core.hud.tablist.enabled"), Mockito.anyBoolean())).thenReturn(true);
        Mockito.when(mockRankService.getCached(Mockito.any())).thenReturn(network.skypvp.shared.RankRecord.DEFAULT);

        tabListService = new TabListService(mockPlugin, mockRankService);
        client = new MockPacketClient();
    }


}
