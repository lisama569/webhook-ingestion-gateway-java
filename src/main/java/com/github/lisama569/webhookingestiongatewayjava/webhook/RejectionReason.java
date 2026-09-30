package com.github.lisama569.webhookingestiongatewayjava.webhook;

/**
 * 请求被拒绝的可区分原因。
 */
public enum RejectionReason {
    UNKNOWN_PARTNER,
    INVALID_SIGNATURE,
    TIMESTAMP_EXPIRED,
    QUOTA_EXCEEDED
}
