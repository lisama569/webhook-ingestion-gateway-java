package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.webhookingestiongatewayjava.webhook.SignatureVerifier;
import com.github.lisama569.webhookingestiongatewayjava.webhook.WebhookRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "webhook.timestamp-tolerance-seconds=60",
        "webhook.partners.acme.secret=acme-secret",
        "webhook.partners.acme.quota-per-window=100",
        "webhook.partners.tiny.secret=tiny-secret",
        "webhook.partners.tiny.quota-per-window=2"
})
@AutoConfigureMockMvc
class WebhookControllerTest {

    private static final Logger log = LoggerFactory.getLogger(WebhookControllerTest.class);

    @Autowired
    MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
            String partner, String secret, WebhookRequest req, long timestamp) throws Exception {
        String canonical = req.eventId() + "|" + req.eventSeq() + "|" + req.eventType() + "|" + req.payload();
        String sig = SignatureVerifier.computeSignature(secret, partner, timestamp, canonical);
        return post("/webhooks/" + partner)
                .header("X-Timestamp", timestamp)
                .header("X-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req));
    }

    @Test
    void appliedThenDuplicate() throws Exception {
        long now = Instant.now().getEpochSecond();
        WebhookRequest req = new WebhookRequest("evt-http-1", 1, "order.created", "{\"a\":1}");
        mvc.perform(signed("acme", "acme-secret", req, now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));
        mvc.perform(signed("acme", "acme-secret", req, now))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));
        log.info("assert eventId=evt-http-1 partner=acme rationale=duplicate-is-idempotent-success");
    }

    @Test
    void expiredTimestampRejected() throws Exception {
        long stale = Instant.now().getEpochSecond() - 3600;
        WebhookRequest req = new WebhookRequest("evt-http-2", 1, "order.created", "{}");
        mvc.perform(signed("acme", "acme-secret", req, stale))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("TIMESTAMP_EXPIRED"));
        log.info("assert eventId=evt-http-2 partner=acme rationale=expired-timestamp-400");
    }

    @Test
    void badSignatureRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        WebhookRequest req = new WebhookRequest("evt-http-3", 1, "order.created", "{}");
        mvc.perform(signed("acme", "wrong-secret", req, now))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("INVALID_SIGNATURE"));
        log.info("assert eventId=evt-http-3 partner=acme rationale=bad-signature-401");
    }

    @Test
    void unknownPartnerRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        WebhookRequest req = new WebhookRequest("evt-http-4", 1, "order.created", "{}");
        mvc.perform(signed("ghost", "whatever", req, now))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_PARTNER"));
        log.info("assert eventId=evt-http-4 partner=ghost rationale=unknown-partner-401");
    }

    @Test
    void quotaExceededRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        for (int i = 1; i <= 2; i++) {
            mvc.perform(signed("tiny", "tiny-secret",
                            new WebhookRequest("evt-tiny-" + i, 1, "t", "p"), now))
                    .andExpect(status().isOk());
        }
        mvc.perform(signed("tiny", "tiny-secret",
                        new WebhookRequest("evt-tiny-3", 1, "t", "p"), now))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("QUOTA_EXCEEDED"));
        log.info("assert partner=tiny quota=2 rationale=third-request-rejected-429");
    }

    @Test
    void deadLettersEndpointQueryable() throws Exception {
        mvc.perform(get("/webhooks/dead-letters"))
                .andExpect(status().isOk());
        log.info("assert endpoint=/webhooks/dead-letters rationale=queryable");
    }
}
