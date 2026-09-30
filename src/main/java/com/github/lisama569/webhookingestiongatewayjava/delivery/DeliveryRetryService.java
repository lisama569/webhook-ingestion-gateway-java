package com.github.lisama569.webhookingestiongatewayjava.delivery;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.event.EventRecord;
import com.github.lisama569.webhookingestiongatewayjava.event.EventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 失败投递的有限重试；重试耗尽后转入死信存储，不丢弃。
 */
@Service
public class DeliveryRetryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRetryService.class);

    private final WebhookProperties properties;
    private final DeadLetterStore deadLetterStore;
    private final EventDeliveryHandler handler;

    public DeliveryRetryService(WebhookProperties properties, DeadLetterStore deadLetterStore,
                                EventDeliveryHandler handler) {
        this.properties = properties;
        this.deadLetterStore = deadLetterStore;
        this.handler = handler;
    }

    /** 同步尝试投递并按需重试；耗尽后写入死信。 */
    public void deliverWithRetry(EventRecord record) {
        int maxAttempts = properties.getMaxRetryAttempts();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                handler.deliver(record);
                record.setStatus(EventStatus.CONFIRMED);
                log.info("delivered eventId={} partnerId={} attempt={}", 
                        record.getEventId(), record.getPartnerId(), attempt);
                return;
            } catch (Exception e) {
                lastError = e;
                record.setStatus(EventStatus.DELIVERY_FAILED);
                log.warn("delivery-failed eventId={} partnerId={} attempt={}/{} error={}",
                        record.getEventId(), record.getPartnerId(), attempt, maxAttempts, e.getMessage());
            }
        }
        record.setStatus(EventStatus.DEAD_LETTERED);
        deadLetterStore.add(new DeadLetter(record.getEventId(), record.getPartnerId(),
                maxAttempts, String.valueOf(lastError), Instant.now()));
        log.error("dead-lettered eventId={} partnerId={} attempts={} rationale=retry-exhausted",
                record.getEventId(), record.getPartnerId(), maxAttempts);
    }
}
