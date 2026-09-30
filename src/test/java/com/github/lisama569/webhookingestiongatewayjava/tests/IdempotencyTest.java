package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 幂等：同一事件被重复推送时只有第一次生效，后续均判 DUPLICATE。
 */
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    private TestSupport support;
    private final AtomicInteger handled = new AtomicInteger();

    @BeforeEach
    void setUp() {
        long now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        support = TestSupport.custom(clock, 100, 5, 3, envelope -> handled.incrementAndGet());
    }

    @Test
    void repeatedDeliveryOfSameEventAppliesOnce() {
        String eventId = "e-dup-1";
        String body = support.body(eventId, "order.paid", 1);

        IngestionResult first = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);
        IngestionResult second = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);
        IngestionResult third = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);

        log.info("eventId={} eventPartner={} 判定序列={},{},{} 依据=幂等键partnerId:eventId+载荷哈希一致,业务处理器仅触发{}次",
                eventId, TestSupport.ACME, first.disposition(), second.disposition(), third.disposition(), handled.get());

        assertEquals(Disposition.ACCEPTED, first.disposition());
        assertEquals(EventStatus.CONFIRMED, first.status());
        assertEquals(Disposition.DUPLICATE, second.disposition());
        assertEquals(Disposition.DUPLICATE, third.disposition());
        assertEquals(1, handled.get(), "同一事件业务处理只能生效一次");
        assertEquals(EventStatus.CONFIRMED, second.status(), "重复投递返回已确认状态且不回退");
    }

    @Test
    void sameEventFromDifferentPartnersAreIndependent() {
        String eventId = "e-dup-2";
        String body = support.body(eventId, "order.paid", 1);

        IngestionResult byAcme = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);
        IngestionResult byGlobex = support.deliver(TestSupport.GLOBEX, TestSupport.GLOBEX_SECRET, body);

        log.info("eventId={} eventPartners={},{} 判定={},{} 依据=幂等键含合作方,不同合作方互不影响",
                eventId, TestSupport.ACME, TestSupport.GLOBEX, byAcme.disposition(), byGlobex.disposition());

        assertEquals(Disposition.ACCEPTED, byAcme.disposition());
        assertEquals(Disposition.ACCEPTED, byGlobex.disposition());
        assertEquals(2, handled.get());
    }
}
