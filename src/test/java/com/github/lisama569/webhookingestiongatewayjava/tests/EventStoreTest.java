package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.event.EventStore;
import com.github.lisama569.webhookingestiongatewayjava.event.ProcessOutcome;
import com.github.lisama569.webhookingestiongatewayjava.webhook.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {

    private static final Logger log = LoggerFactory.getLogger(EventStoreTest.class);
    private static final String PARTNER = "acme";

    private final EventStore store = new EventStore();

    @Test
    void duplicateDeliveryAppliedOnlyOnce() {
        WebhookRequest req = new WebhookRequest("evt-dup-1", 1, "order.created", "{\"a\":1}");
        EventStore.Decision first = store.process(PARTNER, req);
        EventStore.Decision second = store.process(PARTNER, req);
        log.info("assert eventId={} partner={} first={} second={} rationale={}",
                req.eventId(), PARTNER, first.outcome(), second.outcome(), second.rationale());
        assertEquals(ProcessOutcome.APPLIED, first.outcome());
        assertEquals(ProcessOutcome.DUPLICATE, second.outcome());
    }

    @Test
    void concurrentSameEventAppliedExactlyOnce() throws Exception {
        int threads = 16;
        WebhookRequest req = new WebhookRequest("evt-conc-1", 1, "order.created", "{\"a\":1}");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EventStore.Decision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return store.process(PARTNER, req);
            }));
        }
        start.countDown();
        int applied = 0, duplicate = 0;
        for (Future<EventStore.Decision> f : futures) {
            EventStore.Decision d = f.get();
            if (d.outcome() == ProcessOutcome.APPLIED) applied++;
            if (d.outcome() == ProcessOutcome.DUPLICATE) duplicate++;
        }
        pool.shutdown();
        log.info("assert eventId={} partner={} applied={} duplicate={} rationale=exactly-once-under-concurrency",
                req.eventId(), PARTNER, applied, duplicate);
        assertEquals(1, applied);
        assertEquals(threads - 1, duplicate);
    }

    @Test
    void outOfOrderEventDoesNotRegressState() {
        store.process(PARTNER, new WebhookRequest("evt-ooo-1", 5, "order.updated", "{\"v\":5}"));
        EventStore.Decision late = store.process(PARTNER,
                new WebhookRequest("evt-ooo-1", 3, "order.updated", "{\"v\":3}"));
        log.info("assert eventId=evt-ooo-1 partner={} outcome={} rationale={}",
                PARTNER, late.outcome(), late.rationale());
        assertEquals(ProcessOutcome.STALE_REJECTED, late.outcome());
        assertEquals(5, late.record().getEventSeq());
    }

    @Test
    void conflictingPayloadSameSeqIsDistinguishable() {
        store.process(PARTNER, new WebhookRequest("evt-conf-1", 1, "order.created", "{\"a\":1}"));
        EventStore.Decision conflict = store.process(PARTNER,
                new WebhookRequest("evt-conf-1", 1, "order.created", "{\"a\":2}"));
        log.info("assert eventId=evt-conf-1 partner={} outcome={} rationale={}",
                PARTNER, conflict.outcome(), conflict.rationale());
        assertEquals(ProcessOutcome.CONFLICT, conflict.outcome());
    }

    @Test
    void newerSeqAdvancesState() {
        store.process(PARTNER, new WebhookRequest("evt-adv-1", 1, "order.created", "{\"v\":1}"));
        EventStore.Decision newer = store.process(PARTNER,
                new WebhookRequest("evt-adv-1", 2, "order.updated", "{\"v\":2}"));
        log.info("assert eventId=evt-adv-1 partner={} outcome={} rationale={}",
                PARTNER, newer.outcome(), newer.rationale());
        assertEquals(ProcessOutcome.APPLIED, newer.outcome());
        assertEquals(2, newer.record().getEventSeq());
    }
}
