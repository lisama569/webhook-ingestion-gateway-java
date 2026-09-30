package com.github.lisama569.webhookingestiongatewayjava.security;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import com.github.lisama569.webhookingestiongatewayjava.crypto.HmacSigner;
import com.github.lisama569.webhookingestiongatewayjava.domain.RejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 合作方身份、时效与签名校验。
 *
 * <p>校验顺序与可区分拒绝原因：
 * <ol>
 *   <li>合作方未登记 / 密钥未知 -> {@link RejectionReason#UNKNOWN_PARTNER}</li>
 *   <li>时间戳超出 webhook.replay-window-seconds 窗口 -> {@link RejectionReason#TIMESTAMP_EXPIRED}
 *       （防重放：过期或来自未来的请求都拒绝）</li>
 *   <li>HMAC 签名不匹配 -> {@link RejectionReason#SIGNATURE_INVALID}</li>
 * </ol>
 */
@Component
public class CallbackAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(CallbackAuthenticator.class);

    private final WebhookProperties properties;
    private final HmacSigner signer;
    private final Clock clock;

    public CallbackAuthenticator(WebhookProperties properties, HmacSigner signer, Clock clock) {
        this.properties = properties;
        this.signer = signer;
        this.clock = clock;
    }

    public record AuthOutcome(boolean ok, RejectionReason reason, String message, WebhookProperties.Partner partner) {
        public static AuthOutcome success(WebhookProperties.Partner partner) {
            return new AuthOutcome(true, null, "ok", partner);
        }

        public static AuthOutcome fail(RejectionReason reason, String message) {
            return new AuthOutcome(false, reason, message, null);
        }
    }

    public AuthOutcome authenticate(String partnerId, long timestamp, String payloadHash, String signature) {
        WebhookProperties.Partner partner = properties.getPartners().get(partnerId);
        if (partner == null || partner.getSecret() == null || partner.getSecret().isBlank()) {
            log.warn("鉴权拒绝 eventPartner={} reason=UNKNOWN_PARTNER 依据=合作方标识未登记或密钥未知", partnerId);
            return AuthOutcome.fail(RejectionReason.UNKNOWN_PARTNER,
                    "unknown partner: " + partnerId);
        }

        long now = clock.millis();
        long skew = Math.abs(now - timestamp);
        long windowMillis = properties.getReplayWindowSeconds() * 1000L;
        if (skew > windowMillis) {
            log.warn("鉴权拒绝 eventPartner={} reason=TIMESTAMP_EXPIRED 依据=时间戳偏差{}ms超过窗口{}ms(now={},timestamp={})",
                    partnerId, skew, windowMillis, now, timestamp);
            return AuthOutcome.fail(RejectionReason.TIMESTAMP_EXPIRED,
                    "timestamp outside replay window: skew=" + skew + "ms, window=" + windowMillis + "ms");
        }

        if (!signer.verify(partner.getSecret(), partnerId, timestamp, payloadHash, signature)) {
            log.warn("鉴权拒绝 eventPartner={} reason=SIGNATURE_INVALID 依据=HMAC签名与服务端计算不一致", partnerId);
            return AuthOutcome.fail(RejectionReason.SIGNATURE_INVALID, "signature verification failed");
        }

        log.debug("鉴权通过 eventPartner={}", partnerId);
        return AuthOutcome.success(partner);
    }
}
