package com.github.lisama569.webhookingestiongatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 回调网关可配置项：签名时效窗口、重试上限、各合作方密钥与配额。
 */
@Component
@ConfigurationProperties(prefix = "webhook")
public class WebhookProperties {

    public static class Partner {
        private String secret;
        private int quotaPerWindow;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public int getQuotaPerWindow() { return quotaPerWindow; }
        public void setQuotaPerWindow(int quotaPerWindow) { this.quotaPerWindow = quotaPerWindow; }
    }

    private long timestampToleranceSeconds = 300;
    private int maxRetryAttempts = 3;
    private long rateWindowSeconds = 60;
    private Map<String, Partner> partners = new HashMap<>();

    public long getTimestampToleranceSeconds() { return timestampToleranceSeconds; }
    public void setTimestampToleranceSeconds(long v) { this.timestampToleranceSeconds = v; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int v) { this.maxRetryAttempts = v; }
    public long getRateWindowSeconds() { return rateWindowSeconds; }
    public void setRateWindowSeconds(long v) { this.rateWindowSeconds = v; }
    public Map<String, Partner> getPartners() { return partners; }
    public void setPartners(Map<String, Partner> partners) { this.partners = partners; }
}
