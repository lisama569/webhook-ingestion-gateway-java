package com.github.lisama569.webhookingestiongatewayjava.domain;

/**
 * 事件状态机：
 * RECEIVED -> PROCESSING -> CONFIRMED
 *                        -> CONFLICT（同事件不同载荷且版本不更新）
 *                        -> DEAD_LETTER（重试耗尽）
 * 已 CONFIRMED 的事件不会因晚到/乱序投递而回退。
 */
public enum EventStatus {
    RECEIVED,
    PROCESSING,
    CONFIRMED,
    CONFLICT,
    DEAD_LETTER
}
