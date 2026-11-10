package dev.hookrelay.server.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** Workers change an endpoint's circuit under row lock; API edits must not overwrite that. */
class EndpointConcurrencyIT extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void anEditDoesNotUndoACircuitChangeMadeMeanwhile() throws Exception {
        RestClient c = client(newTenant().apiKey());
        String id = c.post().uri("/v1/endpoints").body(Map.of("url", "http://127.0.0.1:9/hook")).retrieve()
                .body(tools.jackson.databind.JsonNode.class).at("/id").asString();

        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForList("select id from endpoints where id = ? for update", id);
            jdbc.update("update endpoints set circuit_state = 'OPEN', consecutive_failures = 10 where id = ?", id);
            locked.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        int status = c.patch().uri("/v1/endpoints/{id}", id).body(Map.of("description", "renamed")).retrieve()
                .toBodilessEntity().getStatusCode().value();
        worker.get(10, TimeUnit.SECONDS);

        assertThat(status).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap(
                "select description, circuit_state, consecutive_failures from endpoints where id = ?", id);
        assertThat(row.get("description")).isEqualTo("renamed");
        assertThat(row.get("circuit_state")).isEqualTo("OPEN");
        assertThat(((Number) row.get("consecutive_failures")).intValue()).isEqualTo(10);
    }
}
