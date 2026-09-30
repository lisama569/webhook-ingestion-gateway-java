package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.RejectionReason;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 限流：每合作方独立的每分钟固定窗口；超出配额返回可区分的 RATE_LIMITED。
 */
class RateLimitTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitTest.class);

    private TestSupport support;
    private long now;

    @BeforeEach
    void setUp() {
        now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        support = TestSupport.custom(clock, 100, 5, 3, envelope -> { }); // globex 每分钟 5
    }

    @Test
    void quotaIsEnforcedPerPartnerPerMinute() {
        int accepted = 0;
        int limited = 0;
        IngestionResult last = null;
        for (int i = 1; i <= 8; i++) {
            String body = support.body("e-rate-" + i, "order.paid", 1);
            long ts = now;
            last = support.service.ingest(TestSupport.GLOBEX, ts,
                    support.sign(TestSupport.GLOBEX_SECRET, TestSupport.GLOBEX, ts, body),
                    null, null, null, body);
            if (last.disposition() == Disposition.ACCEPTED) {
                accepted++;
            } else if (last.disposition() == Disposition.REJECTED
                    && last.rejectionReason() == RejectionReason.RATE_LIMITED) {
                limited++;
            }
        }

        log.info("eventId=e-rate-* eventPartner={} 投递8次 放行={} 限流={} 最后判定={}/{} 依据=固定窗口配额=5/分钟",
                TestSupport.GLOBEX, accepted, limited, last.disposition(), last.rejectionReason());

        assertEquals(5, accepted, "配额内 5 次放行");
        assertEquals(3, limited, "超出的 3 次被限流");
        assertEquals(Disposition.REJECTED, last.disposition());
        assertEquals(RejectionReason.RATE_LIMITED, last.rejectionReason());
    }

    @Test
    void quotasAreIndependentBetweenPartners() {
        // globex 用尽自己的 5 个配额
        for (int i = 0; i < 6; i++) {
            String body = support.body("e-rate-ind-" + i, "order.paid", 1);
            long ts = now;
            support.service.ingest(TestSupport.GLOBEX, ts,
                    support.sign(TestSupport.GLOBEX_SECRET, TestSupport.GLOBEX, ts, body),
                    null, null, null, body);
        }
        // acme 拥有自己独立的 100 配额，不受影响
        String body = support.body("e-rate-ind-acme", "order.paid", 1);
        long ts = now;
        IngestionResult byAcme = support.service.ingest(TestSupport.ACME, ts,
                support.sign(TestSupport.ACME_SECRET, TestSupport.ACME, ts, body),
                null, null, null, body);

        log.info("eventId=e-rate-ind-acme eventPartner={} 判定={} 依据=合作方各自独立配额,globex耗尽不影响acme",
                TestSupport.ACME, byAcme.disposition());
        assertEquals(Disposition.ACCEPTED, byAcme.disposition());
    }
}
