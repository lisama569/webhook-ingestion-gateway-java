package com.github.lisama569.webhookingestiongatewayjava.model;

/**
 * 解析后的事件投递。
 *
 * @param partnerId 合作方标识
 * @param eventId   合作方维度内唯一的事件标识（幂等键的一部分）
 * @param eventType 事件类型
 * @param version   单调递增的事件版本，用于乱序判定
 * @param timestamp 事件发生时间（epoch 毫秒）
 * @param payload   业务载荷（原始 JSON 字符串）
 * @param payloadHash 载荷的 SHA-256，用于区分重复与冲突
 */
public record EventEnvelope(
        String partnerId,
        String eventId,
        String eventType,
        long version,
        long timestamp,
        String payload,
        String payloadHash) {
}
