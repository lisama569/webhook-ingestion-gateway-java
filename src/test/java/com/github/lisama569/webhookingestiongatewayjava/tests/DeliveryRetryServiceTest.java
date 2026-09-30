package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.delivery.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.delivery.DeadLetterStore;
import com.github.lisama569.webhookingestiongatewayjava.delivery.DeliveryRetryService;
import com.github.lisama569.webhookingestiongatewayjava.event.EventRecord;
import com.github.lisama569.webhookingestiongatewayjava.event.EventStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryRetryServiceTest {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryServiceTest.class);

    private DeliveryRetryService newService(WebhookProperties props, DeadLetterStore store,
                                            com.github.lisama569.webhookingestiongatewayjava.delivery.EventDeliveryHandler handler) {
        return new DeliveryRetryService(props, store, handler);
    }

    @Test
    void retryExhaustedGoesToDeadLetterAndIsQueryable() {
        WebhookProperties props = new WebhookProperties();
        props.setMaxRetryAttempts(3);
        DeadLetterStore store = new DeadLetterStore();
        AtomicInteger attempts = new AtomicInteger();
        DeliveryRetryService service = newService(props, store, record -> {
            attempts.incrementAndGet();
            throw new RuntimeException("downstream-unavailable");
        });

        EventRecord record = new EventRecord("evt-retry-1", "acme", 1, "hash", EventStatus.RECEIVED);
        service.deliverWithRetry(record);

        List<DeadLetter> deadLetters = store.list();
        log.info("assert eventId={} partner={} attempts={} status={} rationale=retry-exhausted-preserved",
                record.getEventId(), record.getPartnerId(), attempts.get(), record.getStatus());
        assertEquals(3, attempts.get());
        assertEquals(EventStatus.DEAD_LETTERED, record.getStatus());
        assertEquals(1, deadLetters.size());
        assertEquals("evt-retry-1", deadLetters.get(0).eventId());
        assertEquals("acme", deadLetters.get(0).partnerId());
    }

    @Test
    void succeedsBeforeExhaustionConfirms() {
        WebhookProperties props = new WebhookProperties();
        props.setMaxRetryAttempts(3);
        DeadLetterStore store = new DeadLetterStore();
        AtomicInteger attempts = new AtomicInteger();
        DeliveryRetryService service = newService(props, store, record -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("flaky");
            }
        });

        EventRecord record = new EventRecord("evt-retry-2", "acme", 1, "hash", EventStatus.RECEIVED);
        service.deliverWithRetry(record);

        log.info("assert eventId={} partner={} attempts={} status={} rationale=recovered-before-limit",
                record.getEventId(), record.getPartnerId(), attempts.get(), record.getStatus());
        assertEquals(2, attempts.get());
        assertEquals(EventStatus.CONFIRMED, record.getStatus());
        assertTrue(store.list().isEmpty());
    }
}
