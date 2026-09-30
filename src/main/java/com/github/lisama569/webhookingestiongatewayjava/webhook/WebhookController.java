package com.github.lisama569.webhookingestiongatewayjava.webhook;

import com.github.lisama569.webhookingestiongatewayjava.delivery.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.delivery.DeadLetterStore;
import com.github.lisama569.webhookingestiongatewayjava.delivery.DeliveryRetryService;
import com.github.lisama569.webhookingestiongatewayjava.event.EventStore;
import com.github.lisama569.webhookingestiongatewayjava.event.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对外回调接入口。处理顺序：签名校验 -> 限流 -> 幂等/乱序/冲突判定 -> 投递。
 * 所有结论均以可区分的 outcome/reason 返回。
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final RateLimiter rateLimiter;
    private final EventStore eventStore;
    private final DeliveryRetryService deliveryRetryService;
    private final DeadLetterStore deadLetterStore;

    public WebhookController(SignatureVerifier signatureVerifier, RateLimiter rateLimiter,
                             EventStore eventStore, DeliveryRetryService deliveryRetryService,
                             DeadLetterStore deadLetterStore) {
        this.signatureVerifier = signatureVerifier;
        this.rateLimiter = rateLimiter;
        this.eventStore = eventStore;
        this.deliveryRetryService = deliveryRetryService;
        this.deadLetterStore = deadLetterStore;
    }

    @PostMapping("/{partnerId}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String partnerId,
                                                       @RequestHeader("X-Timestamp") long timestamp,
                                                       @RequestHeader("X-Signature") String signature,
                                                       @RequestBody WebhookRequest request) {
        String canonicalBody = canonicalBody(request);
        RejectionReason rejection = signatureVerifier.verify(partnerId, timestamp, signature, canonicalBody);
        if (rejection != null) {
            HttpStatus status = switch (rejection) {
                case UNKNOWN_PARTNER, INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED;
                case TIMESTAMP_EXPIRED -> HttpStatus.BAD_REQUEST;
                case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            };
            return ResponseEntity.status(status).body(Map.of(
                    "result", "REJECTED",
                    "reason", rejection.name(),
                    "partnerId", partnerId,
                    "eventId", request.eventId()));
        }

        if (!rateLimiter.tryAcquire(partnerId)) {
            log.info("reject eventId={} partnerId={} reason=QUOTA_EXCEEDED", request.eventId(), partnerId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "result", "REJECTED",
                    "reason", RejectionReason.QUOTA_EXCEEDED.name(),
                    "partnerId", partnerId,
                    "eventId", request.eventId()));
        }

        EventStore.Decision decision = eventStore.process(partnerId, request);
        if (decision.outcome() == ProcessOutcome.APPLIED) {
            deliveryRetryService.deliverWithRetry(decision.record());
        }

        HttpStatus status = switch (decision.outcome()) {
            case APPLIED, DUPLICATE -> HttpStatus.OK;
            case STALE_REJECTED, CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of(
                "result", decision.outcome().name(),
                "rationale", decision.rationale(),
                "partnerId", partnerId,
                "eventId", request.eventId()));
    }

    @GetMapping("/dead-letters")
    public List<DeadLetter> deadLetters() {
        return deadLetterStore.list();
    }

    /** 签名覆盖的规范化报文，避免依赖原始字节。 */
    static String canonicalBody(WebhookRequest r) {
        return r.eventId() + "|" + r.eventSeq() + "|" + r.eventType() + "|" + r.payload();
    }
}
