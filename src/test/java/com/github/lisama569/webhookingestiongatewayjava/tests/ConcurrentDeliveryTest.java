package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发控制：同一事件被多个线程同时投递，事件键级锁串行化判定，
 * 保证恰好一次 ACCEPTED，其余全部 DUPLICATE，业务处理恰好执行一次。
 */
class ConcurrentDeliveryTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentDeliveryTest.class);

    private TestSupport support;
    private final AtomicInteger handled = new AtomicInteger();

    @BeforeEach
    void setUp() {
        long now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        support = TestSupport.custom(clock, 1000, 1000, 3, envelope -> {
            handled.incrementAndGet();
            Thread.sleep(20); // 放大并发窗口
        });
    }

    @Test
    void concurrentSameEventAppliesExactlyOnce() throws InterruptedException {
        int threads = 32;
        String eventId = "e-concurrent-1";
        String body = support.body(eventId, "order.paid", 1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<IngestionResult> results = new ArrayList<>(threads);
        List<Integer> statusCodes = new ArrayList<>(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IngestionResult r = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET, body);
                    synchronized (results) {
                        results.add(r);
                    }
                } catch (Exception e) {
                    synchronized (statusCodes) {
                        statusCodes.add(-1);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long accepted = results.stream().filter(r -> r.disposition() == Disposition.ACCEPTED).count();
        long duplicate = results.stream().filter(r -> r.disposition() == Disposition.DUPLICATE).count();

        log.info("eventId={} eventPartner={} 并发线程={} 判定统计 ACCEPTED={},DUPLICATE={} 依据=事件键级锁串行化插入",
                eventId, TestSupport.ACME, threads, accepted, duplicate);

        assertEquals(threads, results.size());
        assertEquals(1, accepted, "并发投递同一事件只能有一次生效");
        assertEquals(threads - 1L, duplicate, "其余并发投递必须判定为重复");
        assertEquals(1, handled.get(), "业务处理器必须恰好执行一次");
        assertTrue(statusCodes.isEmpty());
    }
}
