package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.webhook.RateLimiter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterTest.class);

    @Test
    void quotaExceededIsRejected() {
        WebhookProperties props = new WebhookProperties();
        WebhookProperties.Partner acme = new WebhookProperties.Partner();
        acme.setSecret("s");
        acme.setQuotaPerWindow(3);
        props.setPartners(Map.of("acme", acme));
        RateLimiter limiter = new RateLimiter(props);

        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire("acme")) allowed++;
        }
        log.info("assert partner=acme allowed={} quota=3 rationale=quota-exceeded-after-3", allowed);
        assertEquals(3, allowed);
    }

    @Test
    void quotasAreIndependentPerPartner() {
        WebhookProperties props = new WebhookProperties();
        WebhookProperties.Partner a = new WebhookProperties.Partner();
        a.setSecret("s1");
        a.setQuotaPerWindow(1);
        WebhookProperties.Partner b = new WebhookProperties.Partner();
        b.setSecret("s2");
        b.setQuotaPerWindow(2);
        props.setPartners(Map.of("a", a, "b", b));
        RateLimiter limiter = new RateLimiter(props);

        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        assertTrue(limiter.tryAcquire("b"));
        assertFalse(limiter.tryAcquire("b"));
        log.info("assert partner=a quota=1 partner=b quota=2 rationale=independent-quotas");
    }
}
