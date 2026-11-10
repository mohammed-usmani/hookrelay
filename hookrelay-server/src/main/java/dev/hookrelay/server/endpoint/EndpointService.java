package dev.hookrelay.server.endpoint;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.common.Cursor;
import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.crypto.SecretCipher;
import dev.hookrelay.server.crypto.WebhookSigner;
import dev.hookrelay.server.eventtype.EventTypeService;
import dev.hookrelay.server.eventtype.SystemEventTypes;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {

    /** How long a rotated-out secret keeps signing, so receivers can switch over. */
    public static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

    public record Created(Endpoint endpoint, String secret) { }

    private final EndpointRepository repo;
    private final EndpointSecretRepository secrets;
    private final EventTypeService eventTypes;
    private final SsrfGuard ssrf;
    private final SecretCipher cipher;
    private final WebhookSigner signer;
    private final Clock clock;

    EndpointService(EndpointRepository repo, EndpointSecretRepository secrets, EventTypeService eventTypes,
                    SsrfGuard ssrf, SecretCipher cipher, WebhookSigner signer, Clock clock) {
        this.repo = repo;
        this.secrets = secrets;
        this.eventTypes = eventTypes;
        this.ssrf = ssrf;
        this.cipher = cipher;
        this.signer = signer;
        this.clock = clock;
    }

    @Transactional
    public Created create(UUID appId, String url, String description, Set<String> types, Integer rateLimitPerSec) {
        checkUrl(url);
        checkTypes(appId, types);
        Instant now = Times.now(clock);
        Endpoint e = repo.save(new Endpoint(Ids.newId(), appId, url, description, types,
                normalizeRateLimit(rateLimitPerSec), now));
        String secret = signer.newSecret();
        secrets.save(new EndpointSecret(Ids.newId(), e.getId(), cipher.encrypt(secret), now));
        return new Created(e, secret);
    }

    @Transactional(readOnly = true)
    public Endpoint get(UUID appId, UUID id) {
        return repo.findLive(appId, id).orElseThrow(() -> new NotFoundException("endpoint"));
    }

    @Transactional(readOnly = true)
    public CursorPage<Endpoint> list(UUID appId, String cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        Cursor.Position after = cursor == null ? null : Cursor.decode(cursor);
        List<Endpoint> rows = repo.page(appId, after == null ? null : after.createdAt(),
                after == null ? null : after.id(), Limit.of(n + 1));
        return CursorPage.of(rows, n, Endpoint::getCreatedAt, Endpoint::getId, e -> e);
    }

    /** Null arguments mean "unchanged"; a rate limit of 0 removes the limit. */
    @Transactional
    public Endpoint update(UUID appId, UUID id, String url, String description, Set<String> types,
                           Integer rateLimitPerSec) {
        Endpoint e = get(appId, id);
        if (url != null) {
            checkUrl(url);
            e.setUrl(url);
        }
        if (description != null) {
            e.setDescription(description);
        }
        if (types != null) {
            checkTypes(appId, types);
            e.setEventTypes(types);
        }
        if (rateLimitPerSec != null) {
            e.setRateLimitPerSec(normalizeRateLimit(rateLimitPerSec));
        }
        e.touch(Times.now(clock));
        return e;
    }

    @Transactional
    public Endpoint pause(UUID appId, UUID id) {
        Endpoint e = get(appId, id);
        e.setStatus(EndpointStatus.PAUSED, Times.now(clock));
        return e;
    }

    @Transactional
    public Endpoint resume(UUID appId, UUID id) {
        Endpoint e = get(appId, id);
        e.setStatus(EndpointStatus.ACTIVE, Times.now(clock));
        return e;
    }

    @Transactional
    public void delete(UUID appId, UUID id) {
        get(appId, id).markDeleted(Times.now(clock));
    }

    /** Starts signing with a new secret; the previous ones keep signing for 24 hours. */
    @Transactional
    public String rotateSecret(UUID appId, UUID id) {
        Endpoint e = get(appId, id);
        Instant now = Times.now(clock);
        secrets.active(e.getId(), now).forEach(s -> s.expireAt(now.plus(ROTATION_OVERLAP)));
        String secret = signer.newSecret();
        secrets.save(new EndpointSecret(Ids.newId(), e.getId(), cipher.encrypt(secret), now));
        return secret;
    }

    @Transactional(readOnly = true)
    public String currentSecret(UUID appId, UUID id) {
        Endpoint e = get(appId, id);
        return activeSecrets(e.getId(), Times.now(clock)).get(0);
    }

    /** Decrypted secrets that should sign right now, newest first. */
    @Transactional(readOnly = true)
    public List<String> activeSecrets(UUID endpointId, Instant now) {
        return secrets.active(endpointId, now).stream().map(s -> cipher.decrypt(s.getSecretEnc())).toList();
    }

    /** Endpoints that should get an event of this type. */
    @Transactional(readOnly = true)
    public List<Endpoint> subscribers(UUID appId, String eventType) {
        return repo.subscribers(appId, eventType, SystemEventTypes.isSystem(eventType));
    }

    private void checkUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ApiException.unprocessable("invalid-url", "url is not a valid URI");
        }
        try {
            ssrf.checkUrl(uri);
            ssrf.resolveAllowed(uri.getHost());
        } catch (SsrfViolation v) {
            throw ApiException.unprocessable("ssrf-blocked", v.getMessage());
        } catch (UncheckedIOException dns) {
            throw ApiException.unprocessable("unresolvable-host", "host '" + uri.getHost() + "' does not resolve");
        }
    }

    private void checkTypes(UUID appId, Set<String> types) {
        if (!eventTypes.allExist(appId, types)) {
            throw ApiException.unprocessable("unknown-event-type", "one or more event types do not exist");
        }
    }

    private static Integer normalizeRateLimit(Integer perSec) {
        if (perSec == null || perSec == 0) {
            return null;
        }
        if (perSec < 0) {
            throw ApiException.badRequest("invalid-rate-limit", "rateLimitPerSec must be positive");
        }
        return perSec;
    }
}
