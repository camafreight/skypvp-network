package network.skypvp.paper.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.bossbar.BossBar;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.test.MockPacketClient;
import network.skypvp.shared.RankRecord;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class HudIntegrationIT {

    private PaperCorePlugin mockPlugin;
    private HudProviderService hudProviderService;
    private GameModeBehaviorService mockBehaviorService;
    private ScoreboardService scoreboardService;
    private TabListService tabListService;
    private MockPacketClient client;

    @BeforeEach
    public void setup() {
        Server mockServer = Mockito.mock(Server.class);
        mockPlugin = Mockito.mock(PaperCorePlugin.class);
        mockBehaviorService = Mockito.mock(GameModeBehaviorService.class);
        PerformanceMonitorService mockPerf = Mockito.mock(PerformanceMonitorService.class);
        RankService mockRankService = Mockito.mock(RankService.class);

        hudProviderService = new HudProviderService(mockPlugin);

        org.bukkit.plugin.ServicesManager mockServicesManager = Mockito.mock(org.bukkit.plugin.ServicesManager.class);
        Mockito.when(mockServer.getServicesManager()).thenReturn(mockServicesManager);
        Mockito.when(mockBehaviorService.activeModeKey()).thenReturn("test_mode");

        Mockito.when(mockPlugin.getServer()).thenReturn(mockServer);
        Mockito.when(mockPlugin.hudProviderService()).thenReturn(hudProviderService);
        Mockito.when(mockPlugin.gameModeBehaviorService()).thenReturn(mockBehaviorService);
        Mockito.when(mockPlugin.performanceMonitorService()).thenReturn(mockPerf);

        Mockito.when(mockPlugin.serverId()).thenReturn("test_server");
        Mockito.when(mockPlugin.serverRole()).thenReturn(network.skypvp.shared.NetworkServerRole.LOBBY);

        Mockito.when(mockBehaviorService.booleanValue(Mockito.eq("core.hud.scoreboard.enabled"), Mockito.anyBoolean())).thenReturn(true);
        Mockito.when(mockBehaviorService.booleanValue(Mockito.eq("core.hud.tablist.enabled"), Mockito.anyBoolean())).thenReturn(true);
        
        Mockito.when(mockRankService.getCached(Mockito.any())).thenReturn(RankRecord.DEFAULT);

        scoreboardService = new ScoreboardService(mockPlugin, mockRankService);
        tabListService = new TabListService(mockPlugin, mockRankService, null);
        client = new MockPacketClient();

        Mockito.when(mockServer.getOnlinePlayers()).thenReturn((java.util.Collection) List.of(client.getMockPlayer()));
    }


}
