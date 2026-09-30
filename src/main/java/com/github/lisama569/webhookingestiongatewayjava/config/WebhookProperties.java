package com.github.lisama569.webhookingestiongatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关可配置项（application.properties 中以 webhook.* 配置）。
 */
@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    /** 允许的请求时间戳与服务端时钟的最大偏差（秒），用于防重放。 */
    private long replayWindowSeconds = 300;

    /** 业务处理失败时的最大投递尝试次数（含首次）。 */
    private int maxAttempts = 3;

    /** 重试之间的固定退避（毫秒）。 */
    private long retryBackoffMillis = 50;

    /** 合作方配置：key 为合作方标识。 */
    private Map<String, Partner> partners = new LinkedHashMap<>();

    public static class Partner {
        /** 合作方独立的 HMAC 密钥。 */
        private String secret;
        /** 每分钟允许的最大请求数。 */
        private int quotaPerMinute;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getQuotaPerMinute() { return quotaPerMinute; }
        public void setQuotaPerMinute(int quotaPerMinute) { this.quotaPerMinute = quotaPerMinute; }
    }

    public long getReplayWindowSeconds() { return replayWindowSeconds; }
    public void setReplayWindowSeconds(long replayWindowSeconds) { this.replayWindowSeconds = replayWindowSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }
    public Map<String, Partner> getPartners() { return partners; }
    public void setPartners(Map<String, Partner> partners) { this.partners = partners; }
}
