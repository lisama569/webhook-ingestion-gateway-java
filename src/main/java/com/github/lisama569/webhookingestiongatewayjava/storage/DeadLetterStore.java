package com.github.lisama569.webhookingestiongatewayjava.storage;

import com.github.lisama569.webhookingestiongatewayjava.model.DeadLetter;
import com.github.lisama569.webhookingestiongatewayjava.model.StoredEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 死信存储（内存实现）。
 *
 * <p>重试达到上限仍失败的投递不会被丢弃：原样保留载荷、尝试次数与最后错误，
 * 支持按死信 ID 或按合作方+事件查询。
 */
@Component
public class DeadLetterStore {

    private final ConcurrentHashMap<String, DeadLetter> letters = new ConcurrentHashMap<>();

    public DeadLetter save(StoredEvent event, int attempts, String lastError, long nowMillis) {
        String id = "dl-" + UUID.randomUUID();
        DeadLetter letter = new DeadLetter(
                id, event.partnerId(), event.eventId(), event.eventType(), event.version(),
                event.payload(), attempts, lastError, nowMillis);
        letters.put(id, letter);
        return letter;
    }

    public Optional<DeadLetter> find(String deadLetterId) {
        return Optional.ofNullable(letters.get(deadLetterId));
    }

    public List<DeadLetter> findByPartnerAndEvent(String partnerId, String eventId) {
        return letters.values().stream()
                .filter(d -> d.partnerId().equals(partnerId) && d.eventId().equals(eventId))
                .collect(Collectors.toList());
    }

    public List<DeadLetter> findAll() {
        return letters.values().stream()
                .sorted(java.util.Comparator.comparingLong(DeadLetter::createdAtMillis))
                .collect(Collectors.toList());
    }

    public void clear() {
        letters.clear();
    }
}
