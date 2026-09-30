package com.github.lisama569.webhookingestiongatewayjava.webhook;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按合作方的固定窗口限流。每个合作方使用各自独立的配额。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private record Window(long windowStart, AtomicInteger count) {}

    private final WebhookProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(WebhookProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public RateLimiter(WebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @return true 表示放行，false 表示超出配额 */
    public boolean tryAcquire(String partnerId) {
        WebhookProperties.Partner partner = properties.getPartners().get(partnerId);
        int quota = partner != null ? partner.getQuotaPerWindow() : 0;
        long windowSizeMs = properties.getRateWindowSeconds() * 1000;
        long now = clock.millis();
        long windowStart = now - (now % windowSizeMs);

        Window window = windows.compute(partnerId, (id, existing) -> {
            if (existing == null || existing.windowStart() != windowStart) {
                return new Window(windowStart, new AtomicInteger(0));
            }
            return existing;
        });

        int current = window.count().incrementAndGet();
        if (current > quota) {
            log.info("rate-limited partnerId={} count={} quota={} windowStart={}",
                    partnerId, current, quota, windowStart);
            return false;
        }
        return true;
    }
}
