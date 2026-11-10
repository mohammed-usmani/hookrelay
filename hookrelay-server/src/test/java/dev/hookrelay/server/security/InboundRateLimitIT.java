package dev.hookrelay.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

class InboundRateLimitIT extends IntegrationTest {

    @Test
    void burstsBeyondTheLimitGet429WithRetryAfter() {
        RestClient c = client(newTenant().apiKey());
        List<ResponseEntity<Void>> responses = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            responses.add(c.get().uri("/v1/endpoints").retrieve().toBodilessEntity());
        }
        List<ResponseEntity<Void>> limited = responses.stream()
                .filter(r -> r.getStatusCode().value() == 429).toList();
        assertThat(limited).isNotEmpty();
        assertThat(limited.get(0).getHeaders().getFirst("Retry-After")).matches("\\d+");
    }

    @Test
    void limitsArePerKey() {
        RestClient a = client(newTenant().apiKey());
        for (int i = 0; i < 80; i++) {
            a.get().uri("/v1/endpoints").retrieve().toBodilessEntity();
        }
        RestClient b = client(newTenant().apiKey());
        assertThat(b.get().uri("/v1/endpoints").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }
}
