package com.github.lisama569.webhookingestiongatewayjava.event;

import java.time.Instant;

/**
 * 已受理事件的内部记录。
 */
public class EventRecord {
    private final String eventId;
    private final String partnerId;
    private volatile long eventSeq;
    private volatile String payloadHash;
    private volatile EventStatus status;
    private volatile Instant updatedAt;

    public EventRecord(String eventId, String partnerId, long eventSeq, String payloadHash, EventStatus status) {
        this.eventId = eventId;
        this.partnerId = partnerId;
        this.eventSeq = eventSeq;
        this.payloadHash = payloadHash;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public String getPartnerId() { return partnerId; }
    public long getEventSeq() { return eventSeq; }
    public void setEventSeq(long eventSeq) { this.eventSeq = eventSeq; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public Instant getUpdatedAt() { return updatedAt; }
}
