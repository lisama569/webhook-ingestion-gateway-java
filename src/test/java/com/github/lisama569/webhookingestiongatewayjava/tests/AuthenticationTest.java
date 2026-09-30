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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 鉴权与防重放：未知密钥、时间戳过期/未来、签名错误必须拒绝且原因可区分。
 */
class AuthenticationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationTest.class);

    private Clock clock;
    private TestSupport support;
    private long now;

    @BeforeEach
    void setUp() {
        now = TestSupport.fixedNow();
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        support = TestSupport.okHandler(clock);
    }

    @Test
    void unknownPartnerIsRejected() {
        String body = support.body("e-auth-1", "order.paid", 1);
        long ts = now;
        String sig = support.sign("some-random-secret", "unknown-co", ts, body);

        IngestionResult r = support.service.ingest("unknown-co", ts, sig, null, null, null, body);

        log.info("eventId=e-auth-1 eventPartner=unknown-co 判定=REJECTED/{} 依据=合作方标识未登记", r.rejectionReason());
        assertEquals(Disposition.REJECTED, r.disposition());
        assertEquals(RejectionReason.UNKNOWN_PARTNER, r.rejectionReason());
    }

    @Test
    void expiredTimestampIsRejected() {
        String body = support.body("e-auth-2", "order.paid", 1);
        long staleTs = now - (301 * 1000L); // 超出 300 秒窗口
        String sig = support.sign(TestSupport.ACME_SECRET, TestSupport.ACME, staleTs, body);

        IngestionResult r = support.service.ingest(TestSupport.ACME, staleTs, sig, null, null, null, body);

        log.info("eventId=e-auth-2 eventPartner={} 判定=REJECTED/{} 依据=时间戳已过期超出防重放窗口",
                TestSupport.ACME, r.rejectionReason());
        assertEquals(Disposition.REJECTED, r.disposition());
        assertEquals(RejectionReason.TIMESTAMP_EXPIRED, r.rejectionReason());
    }

    @Test
    void futureTimestampIsRejected() {
        String body = support.body("e-auth-3", "order.paid", 1);
        long futureTs = now + (301 * 1000L);
        String sig = support.sign(TestSupport.ACME_SECRET, TestSupport.ACME, futureTs, body);

        IngestionResult r = support.service.ingest(TestSupport.ACME, futureTs, sig, null, null, null, body);

        log.info("eventId=e-auth-3 eventPartner={} 判定=REJECTED/{} 依据=时间戳来自未来超出窗口",
                TestSupport.ACME, r.rejectionReason());
        assertEquals(RejectionReason.TIMESTAMP_EXPIRED, r.rejectionReason());
    }

    @Test
    void wrongSignatureIsRejected() {
        String body = support.body("e-auth-4", "order.paid", 1);
        long ts = now;
        String goodSig = support.sign(TestSupport.ACME_SECRET, TestSupport.ACME, ts, body);
        // 翻转最后一个字符制造不匹配
        char last = goodSig.charAt(goodSig.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        String badSig = goodSig.substring(0, goodSig.length() - 1) + flipped;
        assertNotEquals(goodSig, badSig);

        IngestionResult r = support.service.ingest(TestSupport.ACME, ts, badSig, null, null, null, body);

        log.info("eventId=e-auth-4 eventPartner={} 判定=REJECTED/{} 依据=签名与服务端计算不一致",
                TestSupport.ACME, r.rejectionReason());
        assertEquals(Disposition.REJECTED, r.disposition());
        assertEquals(RejectionReason.SIGNATURE_INVALID, r.rejectionReason());
    }

    @Test
    void partnerCannotUseAnotherPartnerSecret() {
        String body = support.body("e-auth-5", "order.paid", 1);
        long ts = now;
        // acme 身份头 + globex 的密钥签名
        String sig = support.sign(TestSupport.GLOBEX_SECRET, TestSupport.ACME, ts, body);

        IngestionResult r = support.service.ingest(TestSupport.ACME, ts, sig, null, null, null, body);

        log.info("eventId=e-auth-5 eventPartner={} 判定=REJECTED/{} 依据=使用了其它合作方的密钥",
                TestSupport.ACME, r.rejectionReason());
        assertEquals(RejectionReason.SIGNATURE_INVALID, r.rejectionReason());
    }
}
