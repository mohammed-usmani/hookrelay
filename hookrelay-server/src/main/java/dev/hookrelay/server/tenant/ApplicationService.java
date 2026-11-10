package dev.hookrelay.server.tenant;

import dev.hookrelay.server.common.Cursor;
import dev.hookrelay.server.common.CursorPage;
import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.common.Times;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private final ApplicationRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ApplicationService(ApplicationRepository repo, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Application create(String name) {
        Application app = repo.save(new Application(Ids.newId(), name.strip(), Times.now(clock)));
        events.publishEvent(new ApplicationCreated(app.getId()));
        return app;
    }

    @Transactional(readOnly = true)
    public Application get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("application"));
    }

    @Transactional
    public Application update(UUID id, String name, ApplicationStatus status) {
        Application app = get(id);
        if (name != null) {
            app.rename(name.strip(), Times.now(clock));
        }
        if (status != null) {
            app.setStatus(status, Times.now(clock));
        }
        return app;
    }

    @Transactional(readOnly = true)
    public CursorPage<Application> list(String cursor, Integer limit) {
        int n = CursorPage.clampLimit(limit);
        Cursor.Position after = cursor == null ? null : Cursor.decode(cursor);
        List<Application> rows = repo.page(after == null ? null : after.createdAt(),
                after == null ? null : after.id(), Limit.of(n + 1));
        return CursorPage.of(rows, n, Application::getCreatedAt, Application::getId, a -> a);
    }
}
