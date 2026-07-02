package network.skypvp.proxy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AdmissionControlServiceTest {

    @Test
    void enforcesBurstThenRequiresRefill() {
        AtomicLong clock = new AtomicLong(0L);
        AdmissionControlService service = new AdmissionControlService(2, 2, clock::get);

        assertTrue(service.tryAcquireTransferPermit());
        assertTrue(service.tryAcquireTransferPermit());
        assertFalse(service.tryAcquireTransferPermit());

        // 0.5s at 2/s refills exactly one token.
        clock.set(500_000_000L);
        assertTrue(service.tryAcquireTransferPermit());
        assertFalse(service.tryAcquireTransferPermit());
    }

    @Test
    void disableBypassesRateLimit() {
        AtomicLong clock = new AtomicLong(0L);
        AdmissionControlService service = new AdmissionControlService(1, 1, clock::get);

        assertTrue(service.tryAcquireTransferPermit());
        assertFalse(service.tryAcquireTransferPermit());

        service.setEnabled(false);
        assertTrue(service.tryAcquireTransferPermit());
        assertTrue(service.tryAcquireTransferPermit());
    }

    @Test
    void reconfigureUpdatesFutureRefillRate() {
        AtomicLong clock = new AtomicLong(0L);
        AdmissionControlService service = new AdmissionControlService(1, 1, clock::get);

        assertTrue(service.tryAcquireTransferPermit());
        assertFalse(service.tryAcquireTransferPermit());

        service.reconfigure(4, 4);
        clock.set(250_000_000L);
        assertTrue(service.tryAcquireTransferPermit());
    }
}
