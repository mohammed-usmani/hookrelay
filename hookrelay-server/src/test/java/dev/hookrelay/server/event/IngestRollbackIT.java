package dev.hookrelay.server.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.hookrelay.server.outbox.OutboxRepository;
import dev.hookrelay.server.support.IntegrationTest;
import dev.hookrelay.server.support.Workers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

/** Own context because of the spy bean; closed right after so its workers don't linger. */
@DirtiesContext
class IngestRollbackIT extends IntegrationTest {

    @MockitoSpyBean OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitListenerEndpointRegistry listeners;

    @Test
    void failureWhileWritingTheOutboxLeavesNoRows() {
        // this context's workers would otherwise consume other tests' messages and hit the spy concurrently
        Workers.stop(listeners);
        Tenant t = newTenant();
        RestClient c = client(t.apiKey());
        assertThat(c.post().uri("/v1/event-types").body(Map.of("name", "order.created")).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(201);
        assertThat(c.post().uri("/v1/endpoints").body(Map.of("url", "http://127.0.0.1:9/hook")).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(201);
        doThrow(new IllegalStateException("boom")).when(outbox).save(any());

        int status = c.post().uri("/v1/events").contentType(MediaType.APPLICATION_JSON)
                .body("{\"eventType\":\"order.created\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8))
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from events where application_id = ?", Integer.class,
                t.appId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from deliveries where application_id = ?", Integer.class,
                t.appId())).isZero();
    }
}
