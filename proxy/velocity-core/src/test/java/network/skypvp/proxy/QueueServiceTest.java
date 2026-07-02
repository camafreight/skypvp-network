package network.skypvp.proxy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueServiceTest {

    @Test
    void joinPreventsDuplicatesAndTracksPosition() {
        QueueService service = new QueueService();

        QueueService.QueueJoinResult first = service.joinQueue(UUID.randomUUID(), "Alpha", "survival");
        UUID betaId = UUID.randomUUID();
        QueueService.QueueJoinResult second = service.joinQueue(betaId, "Beta", "survival");
        QueueService.QueueJoinResult duplicate = service.joinQueue(betaId, "Beta", "survival");

        assertTrue(first.joined());
        assertEquals(2, second.position());
        assertTrue(duplicate.alreadyQueued());
        assertEquals(2, duplicate.position());
        assertEquals(2, service.sizeOf("survival"));
    }

    @Test
    void leaveIsIdempotentAndClearsPlayerIndex() {
        QueueService service = new QueueService();
        UUID playerId = UUID.randomUUID();

        service.joinQueue(playerId, "Alpha", "minigame");

        assertTrue(service.leaveQueue(playerId).left());
        assertFalse(service.leaveQueue(playerId).left());
        assertFalse(service.isQueued(playerId));
    }

    @Test
    void pollRemovesHeadOfQueueInOrder() {
        QueueService service = new QueueService();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();

        service.joinQueue(alpha, "Alpha", "lobby");
        service.joinQueue(beta, "Beta", "lobby");

        assertEquals(alpha, service.poll("lobby").orElseThrow().playerId());
        assertEquals(beta, service.poll("lobby").orElseThrow().playerId());
        assertTrue(service.poll("lobby").isEmpty());
    }
}
