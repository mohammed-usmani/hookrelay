package dev.hookrelay.server.tenant;

import dev.hookrelay.server.common.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operator API (admin token): manage tenants and their API keys. */
@RestController
@RequestMapping("/v1/admin/applications")
class AdminController {

    record CreateApplication(@NotBlank @Size(max = 200) String name) { }

    record UpdateApplication(@Size(min = 1, max = 200) String name, ApplicationStatus status) { }

    record ApplicationView(UUID id, String name, ApplicationStatus status, Instant createdAt, Instant updatedAt) {
        static ApplicationView of(Application a) {
            return new ApplicationView(a.getId(), a.getName(), a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    record CreateKey(@NotBlank @Size(max = 200) String name) { }

    record KeyView(UUID id, String name, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {
        static KeyView of(ApiKey k) {
            return new KeyView(k.getId(), k.getName(), k.getPrefix(), k.getCreatedAt(), k.getLastUsedAt(), k.getRevokedAt());
        }
    }

    record CreatedKeyView(UUID id, String name, String prefix, String key, Instant createdAt) { }

    private final ApplicationService applications;
    private final ApiKeyService keys;

    AdminController(ApplicationService applications, ApiKeyService keys) {
        this.applications = applications;
        this.keys = keys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationView create(@Valid @RequestBody CreateApplication body) {
        return ApplicationView.of(applications.create(body.name()));
    }

    @GetMapping
    CursorPage<ApplicationView> list(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        CursorPage<Application> page = applications.list(cursor, limit);
        return new CursorPage<>(page.data().stream().map(ApplicationView::of).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    ApplicationView get(@PathVariable UUID id) {
        return ApplicationView.of(applications.get(id));
    }

    @PatchMapping("/{id}")
    ApplicationView update(@PathVariable UUID id, @Valid @RequestBody UpdateApplication body) {
        return ApplicationView.of(applications.update(id, body.name(), body.status()));
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedKeyView createKey(@PathVariable UUID id, @Valid @RequestBody CreateKey body) {
        ApiKeyService.CreatedApiKey created = keys.create(id, body.name());
        ApiKey k = created.key();
        return new CreatedKeyView(k.getId(), k.getName(), k.getPrefix(), created.plaintext(), k.getCreatedAt());
    }

    @GetMapping("/{id}/api-keys")
    CursorPage<KeyView> listKeys(@PathVariable UUID id) {
        // ponytail: keys per app are few, so no pagination; add a cursor if tenants start holding hundreds.
        List<KeyView> views = keys.list(id).stream().map(KeyView::of).toList();
        return new CursorPage<>(views, null);
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeKey(@PathVariable UUID id, @PathVariable UUID keyId) {
        keys.revoke(id, keyId);
    }
}
