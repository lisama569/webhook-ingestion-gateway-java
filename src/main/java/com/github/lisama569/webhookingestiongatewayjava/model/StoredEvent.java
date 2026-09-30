package com.github.lisama569.webhookingestiongatewayjava.model;

import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;

/**
 * 事件存储记录（不可变值对象；状态迁移由存储层产生新副本）。
 */
public record StoredEvent(
        String partnerId,
        String eventId,
        String eventType,
        long version,
        long timestamp,
        String payload,
        String payloadHash,
        EventStatus status,
        int attempts,
        long updatedAtMillis) {

    public StoredEvent withStatus(EventStatus newStatus, long now) {
        return new StoredEvent(partnerId, eventId, eventType, version, timestamp, payload, payloadHash,
                newStatus, attempts, now);
    }

    public StoredEvent withAttempts(int newAttempts, long now) {
        return new StoredEvent(partnerId, eventId, eventType, version, timestamp, payload, payloadHash,
                status, newAttempts, now);
    }
}
