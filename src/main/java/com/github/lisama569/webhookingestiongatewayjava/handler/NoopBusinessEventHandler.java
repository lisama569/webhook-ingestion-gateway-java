package com.github.lisama569.webhookingestiongatewayjava.handler;

import com.github.lisama569.webhookingestiongatewayjava.model.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * 默认业务处理器：直接成功。
 */
@Component
public class NoopBusinessEventHandler implements BusinessEventHandler {

    @Override
    public void handle(EventEnvelope event) {
        // skeleton
    }
}
