package network.skypvp.paper.service;

import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class PerformanceMonitorIT {

    private PaperCorePlugin mockPlugin;
    private PerformanceMonitorService perfService;
    private Server mockServer;

    @BeforeEach
    public void setup() {
        mockServer = Mockito.mock(Server.class);
        mockPlugin = Mockito.mock(PaperCorePlugin.class);
        BukkitScheduler mockScheduler = Mockito.mock(BukkitScheduler.class);
        Logger mockLogger = Mockito.mock(Logger.class);

        Mockito.when(mockPlugin.getServer()).thenReturn(mockServer);
        Mockito.when(mockServer.getScheduler()).thenReturn(mockScheduler);
        Mockito.when(mockServer.getOnlinePlayers()).thenReturn((Collection) List.of());
        Mockito.when(mockPlugin.getLogger()).thenReturn(mockLogger);

        org.bukkit.Server.Spigot mockSpigot = Mockito.mock(org.bukkit.Server.Spigot.class);
        Mockito.when(mockServer.spigot()).thenReturn(mockSpigot);

        perfService = new PerformanceMonitorService(mockPlugin);
    }

    @Test
    public void testProviderChurnTracking() throws Exception {
        // Increment churn
        perfService.incrementProviderChurn();
        perfService.incrementProviderChurn();
        perfService.incrementProviderChurn();

        // Use reflection to call captureSample
        Method captureMethod = PerformanceMonitorService.class.getDeclaredMethod("captureSample");
        captureMethod.setAccessible(true);
        captureMethod.invoke(perfService);

        PerformanceMonitorService.Snapshot snapshot = perfService.latestSnapshot();
        assertEquals(3, snapshot.providerChurnRate(), "Churn rate should be 3");

        // Call again without incrementing
        captureMethod.invoke(perfService);
        snapshot = perfService.latestSnapshot();
        assertEquals(0, snapshot.providerChurnRate(), "Churn rate should reset to 0");
    }

    @Test
    public void testGcPressureTracking() throws Exception {
        Method captureMethod = PerformanceMonitorService.class.getDeclaredMethod("captureSample");
        captureMethod.setAccessible(true);
        
        // Initial call to set baseline
        captureMethod.invoke(perfService);
        
        // Next call should calculate the difference. Since we can't easily fake ManagementFactory, 
        // we just ensure the snapshot populated without crashing and gcPressureMs >= 0.
        captureMethod.invoke(perfService);
        
        PerformanceMonitorService.Snapshot snapshot = perfService.latestSnapshot();
        assertTrue(snapshot.gcPressureMs() >= 0, "GC pressure should be tracked");
    }
}
