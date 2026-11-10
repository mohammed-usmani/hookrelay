package dev.hookrelay.receiver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
    private static final String GOOD = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    private static Clock at(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private static Map<String, String> headers(String signature) {
        return Map.of("webhook-id", "msg_p5jXN8AQM9LWM0D4loKWxJek",
                "webhook-timestamp", "1614265330",
                "webhook-signature", signature);
    }

    @Test
    void acceptsAValidSignature() {
        assertThatCode(() -> new WebhookVerifier(SECRET, at(1614265330)).verify(headers(GOOD), BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsWhenAnyListedSignatureMatches() {
        String both = "v1,Ceo5qEr07ixe2NLpvHk3FH9bwy/WavXrAFQ/9tdO6mc= " + GOOD;
        assertThatCode(() -> new WebhookVerifier(SECRET, at(1614265330)).verify(headers(both), BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsATamperedBody() {
        byte[] tampered = "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new WebhookVerifier(SECRET, at(1614265330)).verify(headers(GOOD), tampered))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void rejectsOldTimestamps() {
        assertThatThrownBy(() -> new WebhookVerifier(SECRET, at(1614265330 + 6 * 60)).verify(headers(GOOD), BODY))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void rejectsMissingHeaders() {
        assertThatThrownBy(() -> new WebhookVerifier(SECRET, at(1614265330)).verify(Map.of(), BODY))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    void rejectsOtherVersions() {
        assertThatThrownBy(() -> new WebhookVerifier(SECRET, at(1614265330))
                .verify(headers(GOOD.replace("v1,", "v2,")), BODY))
                .isInstanceOf(WebhookVerificationException.class);
    }
}
