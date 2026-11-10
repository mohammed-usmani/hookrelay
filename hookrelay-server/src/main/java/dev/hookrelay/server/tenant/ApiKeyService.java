package dev.hookrelay.server.tenant;

import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.common.Secrets;
import dev.hookrelay.server.common.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API keys look like {@code hr_live_<prefix>_<secret>}. The prefix is stored in clear for
 * lookup; the secret is high-entropy random, so a plain SHA-256 is the right hash (a slow
 * password hash would only add latency to every request).
 */
@Service
public class ApiKeyService {

    private static final Pattern FORMAT = Pattern.compile("hr_live_([A-Za-z0-9]{8})_([A-Za-z0-9]{32})");

    public record CreatedApiKey(UUID id, String plaintext, ApiKey key) { }

    public record AuthenticatedKey(UUID applicationId, UUID keyId) { }

    private final ApiKeyRepository repo;
    private final ApplicationService applications;
    private final ApplicationRepository appRepo;
    private final Clock clock;

    ApiKeyService(ApiKeyRepository repo, ApplicationService applications, ApplicationRepository appRepo, Clock clock) {
        this.repo = repo;
        this.applications = applications;
        this.appRepo = appRepo;
        this.clock = clock;
    }

    @Transactional
    public CreatedApiKey create(UUID appId, String name) {
        applications.get(appId);
        String prefix = Secrets.base62(8);
        String secret = Secrets.base62(32);
        ApiKey key = repo.save(new ApiKey(Ids.newId(), appId, prefix, hash(secret), name.strip(), Times.now(clock)));
        return new CreatedApiKey(key.getId(), "hr_live_" + prefix + "_" + secret, key);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID appId) {
        applications.get(appId);
        return repo.findByApplicationIdOrderByCreatedAtDesc(appId);
    }

    @Transactional
    public void revoke(UUID appId, UUID keyId) {
        repo.findByIdAndApplicationId(keyId, appId)
                .orElseThrow(() -> new NotFoundException("api key"))
                .revoke(Times.now(clock));
    }

    @Transactional
    public Optional<UUID> authenticate(String presented) {
        return authenticateKey(presented).map(AuthenticatedKey::applicationId);
    }

    @Transactional
    public Optional<AuthenticatedKey> authenticateKey(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(presented);
        if (!m.matches()) {
            return Optional.empty();
        }
        Optional<ApiKey> found = repo.findByPrefix(m.group(1));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        if (key.getRevokedAt() != null || !MessageDigest.isEqual(key.getSecretHash(), hash(m.group(2)))) {
            return Optional.empty();
        }
        boolean appActive = appRepo.findById(key.getApplicationId())
                .map(a -> a.getStatus() == ApplicationStatus.ACTIVE).orElse(false);
        if (!appActive) {
            return Optional.empty();
        }
        Instant now = Times.now(clock);
        repo.touch(key.getId(), now, now.minus(Duration.ofMinutes(1)));
        return Optional.of(new AuthenticatedKey(key.getApplicationId(), key.getId()));
    }

    private static byte[] hash(String secret) {
        return Secrets.sha256(secret.getBytes(StandardCharsets.UTF_8));
    }
}
