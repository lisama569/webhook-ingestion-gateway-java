package com.github.lisama569.webhookingestiongatewayjava.delivery;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 死信存储：重试耗尽的投递保留在此，支持查询。
 */
@Component
public class DeadLetterStore {

    private final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();

    public void add(DeadLetter deadLetter) {
        deadLetters.add(deadLetter);
    }

    public List<DeadLetter> list() {
        return List.copyOf(deadLetters);
    }
}
