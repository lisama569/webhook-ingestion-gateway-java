package com.github.lisama569.webhookingestiongatewayjava.service;

import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;

/**
 * 回调接入服务：鉴权 -> 限流 -> 幂等/乱序/冲突判定 -> 有限重试处理 -> 死信。
 */
public interface WebhookIngestionService {

    IngestionResult ingest(String partnerId, long timestamp, String signature,
                           String eventId, String eventType, Long version, String payload);
}
