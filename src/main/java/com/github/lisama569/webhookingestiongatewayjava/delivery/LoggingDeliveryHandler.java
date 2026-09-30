package com.github.lisama569.webhookingestiongatewayjava.delivery;

import com.github.lisama569.webhookingestiongatewayjava.event.EventRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认投递处理器：仅记录日志，本地可运行。
 */
@Component
public class LoggingDeliveryHandler implements EventDeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingDeliveryHandler.class);

    @Override
    public void deliver(EventRecord record) {
        log.info("deliver eventId={} partnerId={} status={}", record.getEventId(), record.getPartnerId(), record.getStatus());
    }
}
