package com.github.lisama569.webhookingestiongatewayjava.delivery;

import java.time.Instant;

/**
 * 重试耗尽后保留的失败投递，可查询。
 */
public record DeadLetter(String eventId, String partnerId, int attempts, String lastError, Instant failedAt) {
}
