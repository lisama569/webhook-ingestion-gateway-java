package com.github.lisama569.webhookingestiongatewayjava.event;

import com.github.lisama569.webhookingestiongatewayjava.webhook.WebhookRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件幂等与并发控制：同一 (partnerId, eventId) 只生效一次。
 * 判定规则（在 key 级别串行化）：
 * - 首次到达：APPLIED
 * - 相同 eventSeq 且相同内容：DUPLICATE（幂等成功，不重复生效）
 * - eventSeq 小于已确认序号：STALE_REJECTED（乱序/晚到，不允许状态回退）
 * - 相同 eventSeq 但内容不同，或序号更大但内容冲突：CONFLICT（可区分结论，不静默覆盖）
 */
@Component
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    public record Decision(ProcessOutcome outcome, EventRecord record, String rationale) {}

    private final Map<String, EventRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public Decision process(String partnerId, WebhookRequest request) {
        String key = partnerId + ":" + request.eventId();
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            String hash = sha256(request.payload());
            EventRecord existing = records.get(key);
            Decision decision;
            if (existing == null) {
                EventRecord record = new EventRecord(request.eventId(), partnerId,
                        request.eventSeq(), hash, EventStatus.RECEIVED);
                records.put(key, record);
                decision = new Decision(ProcessOutcome.APPLIED, record, "first-seen");
            } else if (request.eventSeq() < existing.getEventSeq()) {
                decision = new Decision(ProcessOutcome.STALE_REJECTED, existing,
                        "stale-seq incoming=" + request.eventSeq() + " confirmed=" + existing.getEventSeq());
            } else if (request.eventSeq() == existing.getEventSeq()) {
                if (hash.equals(existing.getPayloadHash())) {
                    decision = new Decision(ProcessOutcome.DUPLICATE, existing, "same-seq-same-payload");
                } else {
                    decision = new Decision(ProcessOutcome.CONFLICT, existing,
                            "same-seq-different-payload");
                }
            } else {
                // 更大的序号：推进状态，不回退
                existing.setEventSeq(request.eventSeq());
                existing.setPayloadHash(hash);
                existing.setStatus(EventStatus.RECEIVED);
                decision = new Decision(ProcessOutcome.APPLIED, existing,
                        "newer-seq advanced to " + request.eventSeq());
            }
            log.info("decision eventId={} partnerId={} outcome={} rationale={}",
                    request.eventId(), partnerId, decision.outcome(), decision.rationale());
            return decision;
        }
    }

    private static String sha256(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
