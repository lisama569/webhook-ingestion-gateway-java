package com.github.lisama569.webhookingestiongatewayjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.webhookingestiongatewayjava.crypto.HmacSigner;
import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.domain.RejectionReason;
import com.github.lisama569.webhookingestiongatewayjava.handler.BusinessEventHandler;
import com.github.lisama569.webhookingestiongatewayjava.model.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.model.EventEnvelope;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import com.github.lisama569.webhookingestiongatewayjava.model.StoredEvent;
import com.github.lisama569.webhookingestiongatewayjava.ratelimit.PartnerRateLimiter;
import com.github.lisama569.webhookingestiongatewayjava.retry.RetryExecutor;
import com.github.lisama569.webhookingestiongatewayjava.security.CallbackAuthenticator;
import com.github.lisama569.webhookingestiongatewayjava.storage.DeadLetterStore;
import com.github.lisama569.webhookingestiongatewayjava.storage.EventRecordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 默认接入服务，编排：鉴权 -> 限流 -> 解析 -> 幂等/乱序/冲突判定 -> 有限重试 -> 死信。
 *
 * <p>所有结论均为可区分的 {@link Disposition}，日志带事件标识、合作方与判定依据。
 */
@Service
public class DefaultWebhookIngestionService implements WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebhookIngestionService.class);

    private final CallbackAuthenticator authenticator;
    private final PartnerRateLimiter rateLimiter;
    private final EventRecordStore eventStore;
    private final DeadLetterStore deadLetterStore;
    private final RetryExecutor retryExecutor;
    private final BusinessEventHandler handler;
    private final HmacSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DefaultWebhookIngestionService(CallbackAuthenticator authenticator,
                                          PartnerRateLimiter rateLimiter,
                                          EventRecordStore eventStore,
                                          DeadLetterStore deadLetterStore,
                                          RetryExecutor retryExecutor,
                                          BusinessEventHandler handler,
                                          HmacSigner signer,
                                          ObjectMapper objectMapper,
                                          Clock clock) {
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
        this.eventStore = eventStore;
        this.deadLetterStore = deadLetterStore;
        this.retryExecutor = retryExecutor;
        this.handler = handler;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public IngestionResult ingest(String partnerId, long timestamp, String signature,
                                  String eventId, String eventType, Long version, String rawPayload) {
        long now = clock.millis();

        // 1) 身份 / 时效 / 签名校验（拒绝原因可区分）
        String payloadHash = signer.sha256Hex(rawPayload == null ? "" : rawPayload);
        CallbackAuthenticator.AuthOutcome auth =
                authenticator.authenticate(partnerId, timestamp, payloadHash, signature);
        if (!auth.ok()) {
            return IngestionResult.rejected(auth.reason(), partnerId, eventId, auth.message());
        }

        // 2) 合作方独立配额限流（鉴权通过后才占用配额）
        int quota = auth.partner().getQuotaPerMinute();
        if (!rateLimiter.tryAcquire(partnerId, quota)) {
            log.warn("限流拒绝 eventId={} eventPartner={} reason=RATE_LIMITED 依据=超过每分钟配额{}",
                    eventId, partnerId, quota);
            return IngestionResult.rejected(RejectionReason.RATE_LIMITED, partnerId, eventId,
                    "quota per minute exceeded: " + quota);
        }

        // 3) 解析事件体
        EventEnvelope event;
        String storedPayload;
        try {
            JsonNode node = objectMapper.readTree(rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload);
            String id = textOrNull(node.get("eventId"), eventId);
            String type = textOrNull(node.get("eventType"), eventType);
            long ver = node.hasNonNull("version") ? node.get("version").asLong() : (version == null ? 0L : version);
            long eventTime = node.hasNonNull("timestamp") ? node.get("timestamp").asLong() : timestamp;
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("missing required field: eventId");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("missing required field: eventType");
            }
            storedPayload = objectMapper.writeValueAsString(node);
            event = new EventEnvelope(partnerId, id, type, ver, eventTime, storedPayload, payloadHash);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid event payload: " + e.getMessage(), e);
        }

        // 4) 幂等 / 乱序 / 冲突判定（事件键级串行）
        EventRecordStore.InsertResult insert = eventStore.insertIfFirst(event, now);
        return switch (insert.decision()) {
            case DUPLICATE -> {
                log.info("判定=DUPLICATE eventId={} eventPartner={} 依据=同幂等键且载荷哈希一致,当前状态={}",
                        event.eventId(), partnerId, insert.stored().status());
                yield new IngestionResult(Disposition.DUPLICATE, insert.stored().status(), null,
                        partnerId, event.eventId(), insert.stored().version(), null,
                        "duplicate delivery; event already " + insert.stored().status());
            }
            case STALE_VERSION -> {
                log.info("判定=STALE_VERSION eventId={} eventPartner={} 依据=新版本{}不大于已确认版本{},不回退状态",
                        event.eventId(), partnerId, event.version(), insert.stored().version());
                yield new IngestionResult(Disposition.STALE_VERSION, insert.stored().status(), null,
                        partnerId, event.eventId(), insert.stored().version(), null,
                        "stale/out-of-order event v" + event.version()
                                + "; confirmed version stays v" + insert.stored().version());
            }
            case CONFLICT -> {
                log.warn("判定=CONFLICT eventId={} eventPartner={} 依据=同幂等键载荷哈希不同(新v{}/已存v{}),不静默覆盖,保持状态={}",
                        event.eventId(), partnerId, event.version(), insert.stored().version(),
                        insert.stored().status());
                yield new IngestionResult(Disposition.CONFLICT, insert.stored().status(), null,
                        partnerId, event.eventId(), insert.stored().version(), null,
                        "conflicting payload for same eventId; stored state kept unchanged");
            }
            case INSERTED -> processFirstDelivery(event, now);
        };
    }

    private IngestionResult processFirstDelivery(EventEnvelope event, long now) {
        eventStore.updateStatus(event.partnerId(), event.eventId(),
                s -> s.withStatus(EventStatus.PROCESSING, clock.millis()));

        RetryExecutor.AttemptOutcome outcome = retryExecutor.execute(() -> handler.handle(event));

        if (outcome.success()) {
            eventStore.updateStatus(event.partnerId(), event.eventId(),
                    s -> s.withAttempts(outcome.attempts(), clock.millis())
                            .withStatus(EventStatus.CONFIRMED, clock.millis()));
            StoredEvent confirmed = eventStore.find(event.partnerId(), event.eventId()).orElseThrow();
            log.info("判定=ACCEPTED eventId={} eventPartner={} attempts={} 依据=首次投递且下游处理成功,状态=CONFIRMED",
                    event.eventId(), event.partnerId(), outcome.attempts());
            return new IngestionResult(Disposition.ACCEPTED, EventStatus.CONFIRMED, null,
                    event.partnerId(), event.eventId(), confirmed.version(), null,
                    "event accepted and confirmed after " + outcome.attempts() + " attempt(s)");
        }

        // 重试耗尽：状态置 DEAD_LETTER 并保留死信，保证可查询
        StoredEvent failed = eventStore.find(event.partnerId(), event.eventId()).orElseThrow();
        DeadLetter letter = deadLetterStore.save(
                failed.withAttempts(outcome.attempts(), clock.millis()),
                outcome.attempts(), outcome.lastError(), clock.millis());
        eventStore.updateStatus(event.partnerId(), event.eventId(),
                s -> s.withAttempts(outcome.attempts(), clock.millis())
                        .withStatus(EventStatus.DEAD_LETTER, clock.millis()));
        log.error("判定=RETRY_EXHAUSTED eventId={} eventPartner={} attempts={} deadLetterId={} 依据=重试达上限仍失败,error={}",
                event.eventId(), event.partnerId(), outcome.attempts(), letter.deadLetterId(), outcome.lastError());
        return new IngestionResult(Disposition.RETRY_EXHAUSTED, EventStatus.DEAD_LETTER, null,
                event.partnerId(), event.eventId(), failed.version(), letter.deadLetterId(),
                "delivery failed after " + outcome.attempts() + " attempts; retained as dead letter");
    }

    private static String textOrNull(JsonNode node, String fallback) {
        if (node != null && !node.isNull()) {
            String v = node.asText();
            return v == null || v.isBlank() ? fallback : v;
        }
        return fallback;
    }
}
