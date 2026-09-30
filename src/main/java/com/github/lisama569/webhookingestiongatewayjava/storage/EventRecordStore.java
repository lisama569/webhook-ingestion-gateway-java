package com.github.lisama569.webhookingestiongatewayjava.storage;

import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.model.EventEnvelope;
import com.github.lisama569.webhookingestiongatewayjava.model.StoredEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 事件记录存储与并发控制（内存实现）。
 *
 * <p>幂等键 = {@code partnerId + ":" + eventId}。对每个键使用独立锁，
 * 使同一事件的并发/重复投递被串行化判定，保证“同一事件只能生效一次”。
 *
 * <p>判定规则（在键级锁内执行）：
 * <ol>
 *   <li>记录不存在 -> INSERTED（首次投递）</li>
 *   <li>已存在且 payloadHash 相同 -> DUPLICATE（重复/并发/重试投递，不重复生效）</li>
 *   <li>已存在、payloadHash 不同且新版本号不大于已存版本号 -> STALE_VERSION（乱序晚到，不回退）</li>
 *   <li>已存在、payloadHash 不同且版本号相同或更高 -> CONFLICT（同键内容不一致，不静默覆盖；
 *       更高版本的不同内容也必须使用新的 eventId，而不是改写既有事件）</li>
 * </ol>
 */
@Component
public class EventRecordStore {

    /** 插入阶段的结论，供服务层给出可区分的处理结论。 */
    public enum InsertDecision { INSERTED, DUPLICATE, STALE_VERSION, CONFLICT }

    public record InsertResult(InsertDecision decision, StoredEvent stored) {
    }

    private final ConcurrentHashMap<String, StoredEvent> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private static String key(String partnerId, String eventId) {
        return partnerId + ":" + eventId;
    }

    public Optional<StoredEvent> find(String partnerId, String eventId) {
        return Optional.ofNullable(events.get(key(partnerId, eventId)));
    }

    /**
     * 以事件键串行插入并给出判定，见类注释。
     */
    public InsertResult insertIfFirst(EventEnvelope event, long nowMillis) {
        String k = key(event.partnerId(), event.eventId());
        Object lock = locks.computeIfAbsent(k, ignored -> new Object());
        synchronized (lock) {
            StoredEvent existing = events.get(k);
            if (existing == null) {
                StoredEvent stored = new StoredEvent(
                        event.partnerId(), event.eventId(), event.eventType(), event.version(),
                        event.timestamp(), event.payload(), event.payloadHash(),
                        EventStatus.RECEIVED, 0, nowMillis);
                events.put(k, stored);
                return new InsertResult(InsertDecision.INSERTED, stored);
            }
            if (event.payloadHash().equals(existing.payloadHash())) {
                return new InsertResult(InsertDecision.DUPLICATE, existing);
            }
            if (event.version() < existing.version()) {
                return new InsertResult(InsertDecision.STALE_VERSION, existing);
            }
            return new InsertResult(InsertDecision.CONFLICT, existing);
        }
    }

    /** 在键级锁内对既有记录做状态迁移（处理成功 / 死信等）。 */
    public void updateStatus(String partnerId, String eventId,
                             Function<StoredEvent, StoredEvent> updater) {
        String k = key(partnerId, eventId);
        Object lock = locks.computeIfAbsent(k, ignored -> new Object());
        synchronized (lock) {
            StoredEvent existing = events.get(k);
            if (existing != null) {
                events.put(k, updater.apply(existing));
            }
        }
    }

    public void clear() {
        events.clear();
        locks.clear();
    }
}
