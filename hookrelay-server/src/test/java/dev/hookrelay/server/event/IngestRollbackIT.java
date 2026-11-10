package dev.hookrelay.server.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.hookrelay.server.outbox.OutboxRepository;
import dev.hookrelay.server.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;

class IngestRollbackIT extends IntegrationTest {

    @MockitoSpyBean OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    @Test
    void failureWhileWritingTheOutboxLeavesNoRows() {
        Tenant t = newTenant();
        RestClient c = client(t.apiKey());
        c.post().uri("/v1/event-types").body(Map.of("name", "order.created")).retrieve().toBodilessEntity();
        c.post().uri("/v1/endpoints").body(Map.of("url", "http://127.0.0.1:9/hook")).retrieve().toBodilessEntity();
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
