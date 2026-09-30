package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.webhook.RejectionReason;
import com.github.lisama569.webhookingestiongatewayjava.webhook.SignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignatureVerifierTest {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifierTest.class);
    private static final long NOW = 1_700_000_000L;

    private SignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        WebhookProperties props = new WebhookProperties();
        props.setTimestampToleranceSeconds(60);
        WebhookProperties.Partner acme = new WebhookProperties.Partner();
        acme.setSecret("acme-secret");
        acme.setQuotaPerWindow(10);
        props.setPartners(Map.of("acme", acme));
        verifier = new SignatureVerifier(props, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    @Test
    void validSignaturePasses() {
        String body = "evt-1|1|order.created|{}";
        String sig = SignatureVerifier.computeSignature("acme-secret", "acme", NOW, body);
        RejectionReason result = verifier.verify("acme", NOW, sig, body);
        log.info("assert partner=acme reason={} rationale=valid-signature-within-window", result);
        assertNull(result);
    }

    @Test
    void expiredTimestampRejected() {
        long stale = NOW - 3600;
        String body = "evt-2|1|order.created|{}";
        String sig = SignatureVerifier.computeSignature("acme-secret", "acme", stale, body);
        RejectionReason result = verifier.verify("acme", stale, sig, body);
        log.info("assert partner=acme reason={} rationale=timestamp-skew-exceeds-tolerance", result);
        assertEquals(RejectionReason.TIMESTAMP_EXPIRED, result);
    }

    @Test
    void wrongSignatureRejected() {
        RejectionReason result = verifier.verify("acme", NOW, "deadbeef", "evt-3|1|t|p");
        log.info("assert partner=acme reason={} rationale=signature-mismatch", result);
        assertEquals(RejectionReason.INVALID_SIGNATURE, result);
    }

    @Test
    void unknownPartnerRejected() {
        RejectionReason result = verifier.verify("nobody", NOW, "whatever", "evt-4|1|t|p");
        log.info("assert partner=nobody reason={} rationale=no-configured-secret", result);
        assertEquals(RejectionReason.UNKNOWN_PARTNER, result);
    }
}
