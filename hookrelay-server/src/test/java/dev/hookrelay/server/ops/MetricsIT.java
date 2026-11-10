package dev.hookrelay.server.ops;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.hookrelay.server.support.DeliveryTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MetricsIT extends DeliveryTestSupport {

    private String scrape() {
        return management().get().uri("/actuator/prometheus").retrieve().body(String.class);
    }

    private double value(String text, String series) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(series) + " ([0-9.eE+-]+)$").matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    @Test
    void aSuccessfulDeliveryShowsUpInPrometheus() {
        receiver.stubFor(post("/m").willReturn(aResponse().withStatus(200)));
        endpoint(receiverUrl("/m"), List.of());
        String series = "hookrelay_deliveries_completed_total{application=\"hookrelay\",outcome=\"succeeded\"}";
        double before = value(scrape(), series);

        String eventId = send("{}");
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(status(eventId)));

        String text = scrape();
        assertThat(value(text, series)).isEqualTo(before + 1);
        assertThat(text).contains("hookrelay_attempt_duration_seconds_bucket");
        assertThat(text).contains("hookrelay_events_ingested_total");
        assertThat(text).contains("hookrelay_attempts_total{application=\"hookrelay\",outcome=\"SUCCESS\"}");
        assertThat(text).contains("hookrelay_outbox_backlog");
        assertThat(text).contains("hookrelay_outbox_lag_seconds");
        assertThat(text).contains("hookrelay_endpoints_circuit{application=\"hookrelay\",state=\"CLOSED\"}");
    }

    @Test
    void readinessChecksDatabaseAndBroker() {
        JsonNode readiness = management().get().uri("/actuator/health/readiness").retrieve().body(JsonNode.class);
        assertThat(readiness.at("/status").asString()).isEqualTo("UP");
        assertThat(readiness.at("/components/db/status").asString()).isEqualTo("UP");
        assertThat(readiness.at("/components/rabbit/status").asString()).isEqualTo("UP");
        assertThat(management().get().uri("/actuator/health/liveness").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
    }
}
