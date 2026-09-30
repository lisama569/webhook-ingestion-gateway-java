package com.github.lisama569.webhookingestiongatewayjava.retry;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 有限次同步重试执行器。
 *
 * <p>最多尝试 webhook.max-attempts 次（含首次），每次失败后固定退避
 * webhook.retry-backoff-millis 毫秒。最终仍失败时返回 success=false 并
 * 保留尝试次数与最后一次错误信息（不吞掉、不丢弃，交由死信处理）。
 */
@Component
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final WebhookProperties properties;

    public RetryExecutor(WebhookProperties properties) {
        this.properties = properties;
    }

    public record AttemptOutcome(boolean success, int attempts, String lastError) {
    }

    /** 执行任务，最多尝试 maxAttempts 次，返回最终结果。 */
    public AttemptOutcome execute(ThrowingTask task) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        long backoff = Math.max(0L, properties.getRetryBackoffMillis());
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                task.run();
                if (attempt > 1) {
                    log.info("投递在第{}次尝试后成功 依据=下游处理未抛异常", attempt);
                }
                return new AttemptOutcome(true, attempt, null);
            } catch (Exception e) {
                lastError = e;
                log.warn("业务处理失败 attempt={}/{} error={}", attempt, maxAttempts, e.toString());
                if (attempt < maxAttempts && backoff > 0) {
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new AttemptOutcome(false, attempt, ie.toString());
                    }
                }
            }
        }
        return new AttemptOutcome(false, maxAttempts,
                lastError == null ? "unknown error" : lastError.toString());
    }

    @FunctionalInterface
    public interface ThrowingTask {
        void run() throws Exception;
    }
}
