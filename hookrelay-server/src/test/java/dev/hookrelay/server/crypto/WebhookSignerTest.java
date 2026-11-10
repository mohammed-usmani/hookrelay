package dev.hookrelay.server.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void matchesStandardWebhooksVector() {
        // From standard-webhooks/libraries/python/tests/test_webhooks.py::test_sign_function
        String sig = signer.sign("msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330L,
                "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8),
                List.of("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"));
        assertThat(sig).isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void twoSecretsGiveTwoSignatures() {
        String sig = signer.sign("msg_1", 1L, "{}".getBytes(StandardCharsets.UTF_8),
                List.of(signer.newSecret(), signer.newSecret()));
        assertThat(sig.split(" ")).hasSize(2).allMatch(s -> s.startsWith("v1,"));
    }

    @Test
    void newSecretsHaveThePrefixAndDiffer() {
        assertThat(signer.newSecret()).startsWith("whsec_").isNotEqualTo(signer.newSecret());
    }
}
