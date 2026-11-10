package dev.hookrelay.server.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt("whsec_abc"))).isEqualTo("whsec_abc");
    }

    @Test
    void samePlaintextEncryptsDifferently() {
        assertThat(cipher.encrypt("whsec_abc")).isNotEqualTo(cipher.encrypt("whsec_abc"));
    }

    @Test
    void tamperingIsDetected() {
        byte[] enc = cipher.encrypt("whsec_abc");
        enc[enc.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe32Bytes() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
