package com.github.lisama569.webhookingestiongatewayjava.domain;

/**
 * 网关对一次投递给出的可区分处理结论。
 */
public enum Disposition {
    /** 首次投递并处理成功。 */
    ACCEPTED,
    /** 同一幂等键的重复投递，不重复生效。 */
    DUPLICATE,
    /** 乱序晚到：版本号不高于已确认版本，不回退状态。 */
    STALE_VERSION,
    /** 同一事件不同载荷且版本相同：冲突，不静默覆盖。 */
    CONFLICT,
    /** 重试达到上限仍失败，已落入死信。 */
    RETRY_EXHAUSTED,
    /** 安全/配额校验未通过被拒绝。 */
    REJECTED
}
