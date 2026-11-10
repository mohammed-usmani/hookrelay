package dev.hookrelay.server.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApiKeyServiceIT extends IntegrationTest {

    @Autowired ApplicationService applications;
    @Autowired ApiKeyService keys;
    @Autowired ApiKeyRepository keyRepo;

    @Test
    void plaintextShownOnceAndOnlyHashStored() {
        UUID appId = applications.create("acme").getId();
        ApiKeyService.CreatedApiKey created = keys.create(appId, "ci");

        assertThat(created.plaintext()).matches("hr_live_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}");
        ApiKey stored = keyRepo.findById(created.id()).orElseThrow();
        assertThat(stored.getSecretHash()).hasSize(32)
                .isNotEqualTo(created.plaintext().getBytes(StandardCharsets.UTF_8));

        assertThat(keys.authenticate(created.plaintext())).contains(appId);
        assertThat(keys.authenticate(created.plaintext() + "x")).isEmpty();
        assertThat(keys.authenticate("nonsense")).isEmpty();
    }

    @Test
    void revokedKeyNoLongerAuthenticates() {
        UUID appId = applications.create("acme").getId();
        ApiKeyService.CreatedApiKey created = keys.create(appId, "ci");
        keys.revoke(appId, created.id());
        assertThat(keys.authenticate(created.plaintext())).isEmpty();
    }
}
