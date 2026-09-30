package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.crypto.HmacSigner;
import com.github.lisama569.webhookingestiongatewayjava.handler.BusinessEventHandler;
import com.github.lisama569.webhookingestiongatewayjava.ratelimit.PartnerRateLimiter;
import com.github.lisama569.webhookingestiongatewayjava.retry.RetryExecutor;
import com.github.lisama569.webhookingestiongatewayjava.security.CallbackAuthenticator;
import com.github.lisama569.webhookingestiongatewayjava.service.DefaultWebhookIngestionService;
import com.github.lisama569.webhookingestiongatewayjava.service.WebhookIngestionService;
import com.github.lisama569.webhookingestiongatewayjava.storage.DeadLetterStore;
import com.github.lisama569.webhookingestiongatewayjava.storage.EventRecordStore;

import java.time.Clock;
import java.util.Map;

/**
 * 测试装配工具：手工 wire 全链路，允许控制时钟、配额、重试参数与业务处理器。
 */
final class TestSupport {

    static final String ACME = "acme";
    static final String ACME_SECRET = "acme-secret-key-2026";
    static final String GLOBEX = "globex";
    static final String GLOBEX_SECRET = "globex-secret-key-2026";

    final HmacSigner signer = new HmacSigner();
    final ObjectMapper objectMapper = new ObjectMapper();
    final EventRecordStore eventStore;
    final DeadLetterStore deadLetterStore;
    final PartnerRateLimiter rateLimiter;
    final WebhookIngestionService service;
    final WebhookProperties properties;
    final Clock clock;

    private TestSupport(Clock clock, int acmeQuota, int globexQuota, int maxAttempts,
                        BusinessEventHandler handler) {
        this.clock = clock;
        this.properties = new WebhookProperties();
        this.properties.setReplayWindowSeconds(300);
        this.properties.setMaxAttempts(maxAttempts);
        this.properties.setRetryBackoffMillis(1);
        this.properties.setPartners(Map.of(
                ACME, partner(ACME_SECRET, acmeQuota),
                GLOBEX, partner(GLOBEX_SECRET, globexQuota)));

        this.eventStore = new EventRecordStore();
        this.deadLetterStore = new DeadLetterStore();
        this.rateLimiter = new PartnerRateLimiter(properties, clock);
        CallbackAuthenticator authenticator = new CallbackAuthenticator(properties, signer, clock);
        RetryExecutor retryExecutor = new RetryExecutor(properties);
        this.service = new DefaultWebhookIngestionService(
                authenticator, rateLimiter, eventStore, deadLetterStore,
                retryExecutor, handler, signer, objectMapper, clock);
    }

    static TestSupport okHandler(Clock clock) {
        return new TestSupport(clock, 100, 5, 3, envelope -> { });
    }

    static TestSupport custom(Clock clock, int acmeQuota, int globexQuota,
                              int maxAttempts, BusinessEventHandler handler) {
        return new TestSupport(clock, acmeQuota, globexQuota, maxAttempts, handler);
    }

    static long fixedNow() {
        return 1_759_267_200_000L; // 2025-10-01T00:00:00Z 附近的固定时刻
    }

    private static WebhookProperties.Partner partner(String secret, int quota) {
        WebhookProperties.Partner p = new WebhookProperties.Partner();
        p.setSecret(secret);
        p.setQuotaPerMinute(quota);
        return p;
    }

    /** 构造合法事件体。 */
    String body(String eventId, String eventType, long version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"version\":" + version + ",\"data\":{\"amount\":100}}";
    }

    /** 用指定合作方密钥对“请求体原文 + 时间戳”签名。 */
    String sign(String secret, String partner, long timestamp, String rawBody) {
        return signer.sign(secret, partner, timestamp, signer.sha256Hex(rawBody));
    }

    /** 以固定时钟的当前时间与合法签名投递。 */
    com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult
    deliver(String partner, String secret, String rawBody) {
        long ts = clock.millis();
        return service.ingest(partner, ts, sign(secret, partner, ts, rawBody),
                null, null, null, rawBody);
    }
}
