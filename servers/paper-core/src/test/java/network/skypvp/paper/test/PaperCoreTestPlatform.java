package network.skypvp.paper.test;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class PaperCoreTestPlatform {

    protected ServerMock server;

    @BeforeEach
    public void setUp() {
        if (!MockBukkit.isMocked()) {
            server = MockBukkit.mock();
        } else {
            server = MockBukkit.getMock();
        }
    }

    @AfterEach
    public void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }
}
