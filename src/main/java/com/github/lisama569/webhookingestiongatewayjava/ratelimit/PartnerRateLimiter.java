package com.github.lisama569.webhookingestiongatewayjava.ratelimit;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按合作方的每分钟固定窗口配额限流。
 *
 * <p>每个 partnerId 独立计数，窗口起点对当前分钟对齐；窗口翻转时清零。
 * 使用 ConcurrentHashMap + 每窗口对象内置同步，保证并发下计数准确。
 */
@Component
public class PartnerRateLimiter {

    private final WebhookProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PartnerRateLimiter(WebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 尝试占用一个配额；超出配额返回 false（拒绝原因由调用方标记为 RATE_LIMITED）。 */
    public boolean tryAcquire(String partnerId, int quotaPerMinute) {
        long currentWindow = clock.millis() / 60_000L;
        Window window = windows.computeIfAbsent(partnerId, k -> new Window(currentWindow));
        return window.tryAcquire(currentWindow, quotaPerMinute);
    }

    /** 测试辅助：清空所有窗口。 */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private long windowStartMinute;
        private int used;

        private Window(long windowStartMinute) {
            this.windowStartMinute = windowStartMinute;
        }

        synchronized boolean tryAcquire(long currentWindow, int quota) {
            if (currentWindow != windowStartMinute) {
                windowStartMinute = currentWindow;
                used = 0;
            }
            if (used >= quota) {
                return false;
            }
            used++;
            return true;
        }
    }
}
