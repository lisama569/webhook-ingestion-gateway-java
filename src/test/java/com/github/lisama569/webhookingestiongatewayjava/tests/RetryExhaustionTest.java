package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.model.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败投递的有限重试：业务处理持续失败时按上限重试；
 * 重试到上限仍失败 -> RETRY_EXHAUSTED，事件 DEAD_LETTER，
 * 原始投递不丢弃，可通过死信存储查询到载荷、次数与最后错误。
 */
class RetryExhaustionTest {

    private static final Logger log = LoggerFactory.getLogger(RetryExhaustionTest.class);

    private TestSupport support;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        long now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        attempts.set(0);
        support = TestSupport.custom(clock, 100, 5, 3, envelope -> {
            int n = attempts.incrementAndGet();
            throw new IllegalStateException("downstream unavailable #" + n);
        });
    }

    @Test
    void retriesUpToLimitThenRetainsDeadLetter() {
        String eventId = "e-retry-1";
        String body = support.body(eventId, "order.paid", 1);

        IngestionResult result = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);

        log.info("eventId={} eventPartner={} 判定={} 状态={} 实际尝试={} deadLetterId={} 依据=3次尝试均失败,转入死信保留",
                eventId, TestSupport.ACME, result.disposition(), result.status(), attempts.get(),
                result.deadLetterId());

        assertEquals(Disposition.RETRY_EXHAUSTED, result.disposition());
        assertEquals(EventStatus.DEAD_LETTER, result.status());
        assertEquals(3, attempts.get(), "必须恰好尝试到上限 3 次");
        assertNotNull(result.deadLetterId());

        // 死信可按 ID 查询，原始载荷与错误被保留
        DeadLetter byId = support.deadLetterStore.find(result.deadLetterId()).orElseThrow();
        assertEquals(eventId, byId.eventId());
        assertEquals(TestSupport.ACME, byId.partnerId());
        assertEquals(3, byId.attempts());
        assertTrue(byId.payload().contains(eventId), "死信必须保留原始载荷");
        assertTrue(byId.lastError().contains("downstream unavailable"), "必须保留最后错误");

        // 也可按合作方+事件查询
        List<DeadLetter> byEvent = support.deadLetterStore
                .findByPartnerAndEvent(TestSupport.ACME, eventId);
        assertFalse(byEvent.isEmpty());
        log.info("eventId={} eventPartner={} 死信查询 全部死信数={} 按事件命中={} 依据=重试耗尽投递未被丢弃",
                eventId, TestSupport.ACME, support.deadLetterStore.findAll().size(), byEvent.size());
        assertEquals(1, support.deadLetterStore.findAll().size());
    }

    @Test
    void transientFailureSucceedsWithinRetryBudget() {
        long now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        AtomicInteger calls = new AtomicInteger();
        TestSupport flaky = TestSupport.custom(clock, 100, 5, 3, envelope -> {
            if (calls.incrementAndGet() < 2) {
                throw new IllegalStateException("transient");
            }
        });
        String body = flaky.body("e-retry-2", "order.paid", 1);
        IngestionResult result = flaky.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);

        log.info("eventId=e-retry-2 eventPartner={} 判定={} 尝试={} 依据=第2次尝试成功,不进入死信",
                TestSupport.ACME, result.disposition(), calls.get());
        assertEquals(Disposition.ACCEPTED, result.disposition());
        assertEquals(EventStatus.CONFIRMED, result.status());
        assertEquals(2, calls.get());
        assertTrue(flaky.deadLetterStore.findAll().isEmpty());
    }
}
