package com.github.lisama569.webhookingestiongatewayjava.web;

import com.github.lisama569.webhookingestiongatewayjava.domain.RejectionReason;
import com.github.lisama569.webhookingestiongatewayjava.model.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import com.github.lisama569.webhookingestiongatewayjava.service.WebhookIngestionService;
import com.github.lisama569.webhookingestiongatewayjava.storage.DeadLetterStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回调接入与死信查询端点。
 *
 * <p>事件字段（eventId/eventType/version/timestamp/data）放在 JSON 请求体中；
 * 签名必须基于请求体原文计算，因此这里以 String 接收原文。
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookIngestionService ingestionService;
    private final DeadLetterStore deadLetterStore;

    public WebhookController(WebhookIngestionService ingestionService, DeadLetterStore deadLetterStore) {
        this.ingestionService = ingestionService;
        this.deadLetterStore = deadLetterStore;
    }

    @PostMapping("/events")
    public ResponseEntity<IngestionResult> receive(
            @RequestHeader("X-Partner-Id") String partnerId,
            @RequestHeader("X-Timestamp") long timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) {

        IngestionResult result = ingestionService.ingest(partnerId, timestamp, signature,
                null, null, null, rawBody);

        HttpStatus status = switch (result.disposition()) {
            case REJECTED -> statusForRejection(result.rejectionReason());
            case RETRY_EXHAUSTED -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }

    private HttpStatus statusForRejection(RejectionReason reason) {
        return switch (reason) {
            case UNKNOWN_PARTNER, SIGNATURE_INVALID -> HttpStatus.UNAUTHORIZED;
            case TIMESTAMP_EXPIRED -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    @GetMapping("/dead-letters")
    public List<DeadLetter> listDeadLetters() {
        return deadLetterStore.findAll();
    }

    @GetMapping("/dead-letters/{id}")
    public ResponseEntity<DeadLetter> getDeadLetter(@PathVariable String id) {
        return deadLetterStore.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
