package dev.hookrelay.receiver;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies a HookRelay (Standard Webhooks) delivery. Plain Java with no framework types, so
 * a receiver can copy this one file. Header names are expected in lower case.
 */
public class WebhookVerifier {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final byte[] key;
    private final Clock clock;

    public WebhookVerifier(String secret, Clock clock) {
        String b64 = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
        this.key = Base64.getDecoder().decode(b64);
        this.clock = clock;
    }

    public void verify(Map<String, String> headers, byte[] body) {
        String id = require(headers, "webhook-id");
        String ts = require(headers, "webhook-timestamp");
        String signatures = require(headers, "webhook-signature");

        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException("invalid webhook-timestamp");
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - seconds) > TOLERANCE.toSeconds()) {
            throw new WebhookVerificationException("timestamp outside the 5 minute tolerance");
        }

        byte[] expected = hmac((id + "." + ts + ".").getBytes(StandardCharsets.UTF_8), body);
        for (String entry : signatures.split(" ")) {
            int comma = entry.indexOf(',');
            if (comma < 0 || !entry.substring(0, comma).equals("v1")) {
                continue;
            }
            byte[] presented;
            try {
                presented = Base64.getDecoder().decode(entry.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, presented)) {
                return;
            }
        }
        throw new WebhookVerificationException("no matching signature");
    }

    private static String require(Map<String, String> headers, String name) {
        String v = headers.get(name);
        if (v == null || v.isBlank()) {
            throw new WebhookVerificationException("missing " + name + " header");
        }
        return v;
    }

    private byte[] hmac(byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(prefix);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
