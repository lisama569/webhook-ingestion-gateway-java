package com.github.lisama569.webhookingestiongatewayjava.model;

/**
 * 重试耗尽后保留的死信投递，可被查询。
 */
public record DeadLetter(
        String deadLetterId,
        String partnerId,
        String eventId,
        String eventType,
        long version,
        String payload,
        int attempts,
        String lastError,
        long createdAtMillis) {
}
