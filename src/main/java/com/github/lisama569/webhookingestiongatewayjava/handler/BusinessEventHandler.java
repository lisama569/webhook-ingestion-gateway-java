package com.github.lisama569.webhookingestiongatewayjava.handler;

import com.github.lisama569.webhookingestiongatewayjava.model.EventEnvelope;

/**
 * 下游业务处理 SPI：抛异常表示处理失败，可被重试。
 */
public interface BusinessEventHandler {

    void handle(EventEnvelope event) throws Exception;
}
