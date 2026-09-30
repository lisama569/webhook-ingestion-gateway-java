package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.webhookingestiongatewayjava.crypto.HmacSigner;
import com.github.lisama569.webhookingestiongatewayjava.handler.BusinessEventHandler;
import com.github.lisama569.webhookingestiongatewayjava.model.EventEnvelope;
import com.github.lisama569.webhookingestiongatewayjava.storage.DeadLetterStore;
import com.github.lisama569.webhookingestiongatewayjava.storage.EventRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路 HTTP 测试：状态码区分、端到端签名校验、限流与死信查询端点。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(WebhookEndToEndTest.class);

    @Autowired private MockMvc mockMvc;
    @Autowired private HmacSigner signer;
    @Autowired private EventRecordStore eventStore;
    @Autowired private DeadLetterStore deadLetterStore;
    @Autowired private ObjectMapper objectMapper;

    // 用 Mockito 替换默认业务处理器，默认成功；个别用例切换为失败
    @MockBean private BusinessEventHandler handler;

    @BeforeEach
    void clean() throws Exception {
        eventStore.clear();
        deadLetterStore.clear();
        doNothing().when(handler).handle(any(EventEnvelope.class));
    }

    private String body(String eventId, long version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"order.paid\",\"version\":" + version + "}";
    }

    @Test
    void happyPathReturnsAccepted() throws Exception {
        long ts = System.currentTimeMillis();
        String body = body("e2e-1", 1);
        String sig = signer.sign("acme-secret-key-2026", "acme", ts, signer.sha256Hex(body));

        log.info("eventId=e2e-1 eventPartner=acme 判定=期望HTTP200/ACCEPTED 依据=合法签名+首次事件");
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", sig)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("ACCEPTED"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void duplicateAndAuthAndTimestampStatusCodes() throws Exception {
        long ts = System.currentTimeMillis();
        String body = body("e2e-2", 1);
        String sig = signer.sign("acme-secret-key-2026", "acme", ts, signer.sha256Hex(body));

        // 第一次成功
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme").header("X-Timestamp", ts)
                        .header("X-Signature", sig).contentType("application/json").content(body))
                .andExpect(status().isOk());

        // 重复 -> 200 DUPLICATE
        log.info("eventId=e2e-2 eventPartner=acme 判定=期望HTTP200/DUPLICATE 依据=重复投递不报错但结论可区分");
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme").header("X-Timestamp", ts)
                        .header("X-Signature", sig).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"));

        // 未知合作方 -> 401
        log.info("eventId=e2e-x eventPartner=mystery 判定=期望HTTP401/UNKNOWN_PARTNER");
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "mystery").header("X-Timestamp", ts)
                        .header("X-Signature", sig).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.rejectionReason").value("UNKNOWN_PARTNER"));

        // 过期 -> 403
        long stale = ts - 10 * 60 * 1000L;
        String staleSig = signer.sign("acme-secret-key-2026", "acme", stale, signer.sha256Hex(body));
        log.info("eventId=e2e-x eventPartner=acme 判定=期望HTTP403/TIMESTAMP_EXPIRED");
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme").header("X-Timestamp", stale)
                        .header("X-Signature", staleSig).contentType("application/json").content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rejectionReason").value("TIMESTAMP_EXPIRED"));

        // 签名错 -> 401 SIGNATURE_INVALID
        log.info("eventId=e2e-x eventPartner=acme 判定=期望HTTP401/SIGNATURE_INVALID");
        mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme").header("X-Timestamp", ts)
                        .header("X-Signature", "Zm9vYmFy").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.rejectionReason").value("SIGNATURE_INVALID"));
    }

    @Test
    void exhaustedDeliveryBecomesQueryableDeadLetter() throws Exception {
        doThrow(new IllegalStateException("boom")).when(handler).handle(any(EventEnvelope.class));

        long ts = System.currentTimeMillis();
        String body = body("e2e-dl", 1);
        String sig = signer.sign("acme-secret-key-2026", "acme", ts, signer.sha256Hex(body));

        log.info("eventId=e2e-dl eventPartner=acme 判定=期望HTTP503/RETRY_EXHAUSTED 依据=处理持续失败重试耗尽");
        String response = mockMvc.perform(post("/webhooks/events")
                        .header("X-Partner-Id", "acme").header("X-Timestamp", ts)
                        .header("X-Signature", sig).contentType("application/json").content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.disposition").value("RETRY_EXHAUSTED"))
                .andReturn().getResponse().getContentAsString();

        String deadLetterId = objectMapper.readTree(response).get("deadLetterId").asText();

        mockMvc.perform(get("/webhooks/dead-letters/" + deadLetterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("e2e-dl"))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.lastError").value("java.lang.IllegalStateException: boom"));

        mockMvc.perform(get("/webhooks/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deadLetterId").value(deadLetterId));
        log.info("eventId=e2e-dl eventPartner=acme 死信查询成功 deadLetterId={} 依据=GET /webhooks/dead-letters 可查到保留投递",
                deadLetterId);
    }
}
