package com.github.lisama569.webhookingestiongatewayjava.domain;

/**
 * 请求被拒绝的可区分原因。
 */
public enum RejectionReason {
    /** 合作方标识未登记 / 密钥未知。 */
    UNKNOWN_PARTNER,
    /** 时间戳超出允许的时间窗口（过期或来自未来）。 */
    TIMESTAMP_EXPIRED,
    /** 签名与服务端计算结果不一致。 */
    SIGNATURE_INVALID,
    /** 超出合作方每分钟配额。 */
    RATE_LIMITED
}
