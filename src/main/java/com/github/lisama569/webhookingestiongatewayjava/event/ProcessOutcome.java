package com.github.lisama569.webhookingestiongatewayjava.event;

/**
 * 幂等/乱序/冲突判定的可区分结论。
 */
public enum ProcessOutcome {
    APPLIED,
    DUPLICATE,
    STALE_REJECTED,
    CONFLICT
}
