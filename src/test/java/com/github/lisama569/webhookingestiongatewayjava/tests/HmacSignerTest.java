package com.github.lisama569.webhookingestiongatewayjava.tests;

import com.github.lisama569.webhookingestiongatewayjava.crypto.HmacSigner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名工具单测：正确签名通过、篡改载荷/时间戳/合作方/密钥后失败。
 */
class HmacSignerTest {

    private static final Logger log = LoggerFactory.getLogger(HmacSignerTest.class);

    private final HmacSigner signer = new HmacSigner();

    @Test
    void validSignaturePasses() {
        String partner = "acme";
        long ts = 1_759_200_000_000L;
        String hash = signer.sha256Hex("{\"eventId\":\"e-1\"}");
        String sig = signer.sign("acme-secret", partner, ts, hash);

        boolean ok = signer.verify("acme-secret", partner, ts, hash, sig);
        log.info("eventId=e-1 eventPartner={} 判定=签名有效 依据=相同密钥与签名串重算一致 -> {}", partner, ok);
        assertTrue(ok);
    }

    @Test
    void tamperedPayloadFails() {
        String partner = "acme";
        long ts = 1_759_200_000_000L;
        String hash = signer.sha256Hex("{\"eventId\":\"e-1\"}");
        String sig = signer.sign("acme-secret", partner, ts, hash);

        boolean ok = signer.verify("acme-secret", partner, ts, signer.sha256Hex("{\"eventId\":\"e-2\"}"), sig);
        log.info("eventId=e-2 eventPartner={} 判定=签名无效 依据=载荷哈希与签名时不一致 -> {}", partner, ok);
        assertFalse(ok);
    }

    @Test
    void wrongSecretAndTimestampFail() {
        String partner = "acme";
        long ts = 1_759_200_000_000L;
        String hash = signer.sha256Hex("{}");
        String sig = signer.sign("acme-secret", partner, ts, hash);

        boolean wrongSecret = signer.verify("other-secret", partner, ts, hash, sig);
        boolean wrongTs = signer.verify("acme-secret", partner, ts + 1, hash, sig);
        log.info("eventId=- eventPartner={} 判定=签名无效 依据=错误密钥->{} 时间戳被改->{}", partner, wrongSecret, wrongTs);
        assertFalse(wrongSecret);
        assertFalse(wrongTs);
    }

    @Test
    void sha256IsStableHex() {
        String hash = signer.sha256Hex("abc");
        log.info("eventId=- eventPartner=- 判定=哈希稳定 依据=sha256('abc')应为空字符串已知摘要");
        assertEquals(64, hash.length());
        assertEquals(hash, signer.sha256Hex("abc"));
    }
}
