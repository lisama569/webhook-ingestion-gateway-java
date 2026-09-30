package com.github.lisama569.webhookingestiongatewayjava.delivery;

import com.github.lisama569.webhookingestiongatewayjava.event.EventRecord;

/**
 * 下游投递处理器，业务方可替换实现。
 */
public interface EventDeliveryHandler {
    void deliver(EventRecord record) throws Exception;
}
