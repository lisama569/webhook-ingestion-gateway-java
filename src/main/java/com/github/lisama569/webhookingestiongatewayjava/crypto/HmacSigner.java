package com.github.lisama569.webhookingestiongatewayjava.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 签名计算与校验。
 *
 * <p>签名内容约定为：{@code partnerId + "." + timestamp + "." + payloadHash}，
 * 其中 payloadHash = 小写十六进制 SHA-256(请求体原文)。签名以标准 Base64 输出。
 */
@Component
public class HmacSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 计算小写十六进制 sha256(payload)。 */
    public String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                out[i * 2] = HEX[(hash[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[hash[i] & 0xF];
            }
            return new String(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 以 base64(HMAC-SHA256(secret, partnerId.timestamp.payloadHash)) 生成签名。 */
    public String sign(String secret, String partnerId, long timestamp, String payloadHash) {
        return Base64.getEncoder().encodeToString(rawMac(secret, partnerId, timestamp, payloadHash));
    }

    /** 常量时间比较签名是否匹配；签名不是合法 Base64 时直接判失败。 */
    public boolean verify(String secret, String partnerId, long timestamp, String payloadHash, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        final byte[] expected = rawMac(secret, partnerId, timestamp, payloadHash);
        final byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] rawMac(String secret, String partnerId, long timestamp, String payloadHash) {
        try {
            String content = partnerId + "." + timestamp + "." + payloadHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}
