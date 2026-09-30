package com.github.lisama569.webhookingestiongatewayjava.event;

/**
 * 事件状态流转：RECEIVED -> CONFIRMED；DELIVERY_FAILED / DEAD_LETTERED 为投递侧状态。
 */
public enum EventStatus {
    RECEIVED,
    CONFIRMED,
    DELIVERY_FAILED,
    DEAD_LETTERED
}
