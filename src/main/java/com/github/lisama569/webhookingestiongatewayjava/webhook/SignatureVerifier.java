package com.github.lisama569.webhookingestiongatewayjava.webhook;

import com.github.lisama569.webhookingestiongatewayjava.config.WebhookProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 校验请求签名与时间戳时效。返回 null 表示通过，否则返回拒绝原因。
 * 签名规则：hex(HMAC-SHA256(secret, partnerId + "\n" + timestamp + "\n" + body))
 */
@Component
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    private final WebhookProperties properties;
    private final Clock clock;

    @Autowired
    public SignatureVerifier(WebhookProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public SignatureVerifier(WebhookProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public RejectionReason verify(String partnerId, long timestamp, String signature, String body) {
        WebhookProperties.Partner partner = properties.getPartners().get(partnerId);
        if (partner == null) {
            log.info("reject partnerId={} reason=UNKNOWN_PARTNER", partnerId);
            return RejectionReason.UNKNOWN_PARTNER;
        }
        long now = clock.instant().getEpochSecond();
        long skew = Math.abs(now - timestamp);
        if (skew > properties.getTimestampToleranceSeconds()) {
            log.info("reject partnerId={} reason=TIMESTAMP_EXPIRED skew={}s tolerance={}s",
                    partnerId, skew, properties.getTimestampToleranceSeconds());
            return RejectionReason.TIMESTAMP_EXPIRED;
        }
        String expected = computeSignature(partner.getSecret(), partnerId, timestamp, body);
        if (signature == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            log.info("reject partnerId={} reason=INVALID_SIGNATURE", partnerId);
            return RejectionReason.INVALID_SIGNATURE;
        }
        return null;
    }

    public static String computeSignature(String secret, String partnerId, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = partnerId + "\n" + timestamp + "\n" + body;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute signature", e);
        }
    }
}
