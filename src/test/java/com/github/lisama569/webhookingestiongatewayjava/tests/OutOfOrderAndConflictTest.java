package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.domain.Disposition;
import com.github.lisama569.webhookingestiongatewayjava.domain.EventStatus;
import com.github.lisama569.webhookingestiongatewayjava.model.IngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 乱序与冲突：
 * - 先收到 v2 确认，再晚到 v1 的不同内容 -> STALE_VERSION，已确认状态不回退；
 * - 同一事件相同版本不同载荷 -> CONFLICT，不静默覆盖；
 * - 同一事件更高版本不同载荷 -> CONFLICT（禁止改写既有事件标识，必须用新 eventId）。
 */
class OutOfOrderAndConflictTest {

    private static final Logger log = LoggerFactory.getLogger(OutOfOrderAndConflictTest.class);

    private TestSupport support;

    @BeforeEach
    void setUp() {
        long now = TestSupport.fixedNow();
        Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        support = TestSupport.okHandler(clock);
    }

    @Test
    void lateLowerVersionDoesNotRollbackConfirmedState() {
        String eventId = "e-ooo-1";
        // 模拟乱序：v2 先到并确认
        IngestionResult v2 = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 2, "SHIPPED"));
        // v1 晚到，内容不同（旧状态）
        IngestionResult v1Late = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 1, "CREATED"));

        log.info("eventId={} eventPartner={} 判定 v2先到={} v1晚到={} 当前状态={} 依据=新版本1不大于已确认版本2,拒绝回退",
                eventId, TestSupport.ACME, v2.disposition(), v1Late.disposition(), v1Late.status());

        assertEquals(Disposition.ACCEPTED, v2.disposition());
        assertEquals(Disposition.STALE_VERSION, v1Late.disposition());
        assertEquals(EventStatus.CONFIRMED, v1Late.status(), "已确认状态不得被晚到事件回退");
        assertEquals(2L, v1Late.currentVersion());
    }

    @Test
    void sameVersionDifferentPayloadIsConflictNotOverwrite() {
        String eventId = "e-conflict-1";
        IngestionResult first = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 1, "amount=100"));
        IngestionResult conflicting = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 1, "amount=999"));

        log.info("eventId={} eventPartner={} 判定 首次={} 冲突={} 保持状态={} 依据=同幂等键同版本但载荷哈希不同,不静默覆盖",
                eventId, TestSupport.ACME, first.disposition(), conflicting.disposition(), conflicting.status());

        assertEquals(Disposition.ACCEPTED, first.disposition());
        assertEquals(Disposition.CONFLICT, conflicting.disposition());
        assertEquals(EventStatus.CONFIRMED, conflicting.status(), "冲突时保留首次确认的状态");

        // 再次投递原始载荷仍然只是重复，不被冲突影响
        IngestionResult repeatOriginal = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 1, "amount=100"));
        assertEquals(Disposition.DUPLICATE, repeatOriginal.disposition());
    }

    @Test
    void higherVersionDifferentPayloadIsAlsoConflict() {
        String eventId = "e-conflict-2";
        support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 1, "amount=100"));
        IngestionResult bumped = support.deliver(TestSupport.ACME, TestSupport.ACME_SECRET,
                bodyWith(eventId, "order.paid", 2, "amount=999"));

        log.info("eventId={} eventPartner={} 判定 v2不同载荷={} 依据=同一事件标识不允许被不同内容改写,应使用新eventId",
                eventId, TestSupport.ACME, bumped.disposition());
        assertEquals(Disposition.CONFLICT, bumped.disposition());
        assertEquals(1L, bumped.currentVersion(), "已存版本不应被改写");
    }

    private String bodyWith(String eventId, String type, long version, String dataMarker) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type
                + "\",\"version\":" + version + ",\"data\":{\"marker\":\"" + dataMarker + "\"}}";
    }
}
