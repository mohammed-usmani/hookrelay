package dev.hookrelay.server.crypto;

import dev.hookrelay.server.common.Secrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs payloads per the Standard Webhooks spec: HMAC-SHA256 over {@code id.timestamp.body},
 * keyed by the base64 part of a {@code whsec_} secret. During secret rotation it signs with
 * every active secret, space-separated, so receivers using either secret keep working.
 */
@Component
public class WebhookSigner {

    public static final String SECRET_PREFIX = "whsec_";

    public String newSecret() {
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(Secrets.randomBytes(24));
    }

    public String sign(String msgId, long timestampSeconds, byte[] body, List<String> secrets) {
        byte[] prefix = (msgId + "." + timestampSeconds + ".").getBytes(StandardCharsets.UTF_8);
        return secrets.stream()
                .map(s -> "v1," + Base64.getEncoder().encodeToString(hmac(key(s), prefix, body)))
                .collect(Collectors.joining(" "));
    }

    private static byte[] key(String secret) {
        String b64 = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
        return Base64.getDecoder().decode(b64);
    }

    private static byte[] hmac(byte[] key, byte[] prefix, byte[] body) {
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
