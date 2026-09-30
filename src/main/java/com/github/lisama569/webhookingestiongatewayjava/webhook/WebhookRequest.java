package com.github.lisama569.webhookingestiongatewayjava.webhook;

/**
 * 合作方推送的业务事件。eventSeq 用于乱序判定，payloadHash 用于冲突判定。
 */
public record WebhookRequest(String eventId, long eventSeq, String eventType, String payload) {
}
