package dev.hookrelay.server.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookSenderTest {

    static WireMockServer wm = new WireMockServer(wireMockConfig().dynamicPort());
    final WebhookSender sender = new WebhookSender(Duration.ofSeconds(2), Duration.ofSeconds(1));
    static final byte[] BODY = "{\"a\":\"ü\",  \"b\":1}".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void start() {
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private URI url() {
        return URI.create(wm.baseUrl() + "/hook");
    }

    @Test
    void twoHundredIsSuccess() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(204)));
        SendResult r = sender.send(url(), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.SUCCESS);
        assertThat(r.status()).isEqualTo(204);
    }

    @Test
    void serverErrorIsHttpError() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(500).withBody("down")));
        SendResult r = sender.send(url(), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.HTTP_ERROR);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.bodySnippet()).isEqualTo("down");
    }

    @Test
    void redirectsAreFailuresAndNotFollowed() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));
        wm.stubFor(get("/elsewhere").willReturn(aResponse().withStatus(200)));
        SendResult r = sender.send(url(), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.REDIRECT);
        assertThat(r.status()).isEqualTo(302);
        assertThat(wm.findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlEqualTo("/elsewhere")))).isEmpty();
    }

    @Test
    void slowReceiverTimesOut() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        long start = System.nanoTime();
        SendResult r = sender.send(url(), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void hugeResponseBodiesAreCappedAt4Kb() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(200).withBody("x".repeat(10_000_000))));
        SendResult r = sender.send(url(), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.SUCCESS);
        assertThat(r.bodySnippet()).hasSizeLessThanOrEqualTo(4096);
    }

    @Test
    void closedPortIsConnectionError() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        SendResult r = sender.send(URI.create("http://127.0.0.1:" + port + "/hook"), Map.of(), BODY);
        assertThat(r.outcome()).isEqualTo(SendResult.Outcome.CONNECTION_ERROR);
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void sendsExactBytesAndHeaders() {
        wm.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));
        sender.send(url(), Map.of("webhook-id", "evt_1"), BODY);
        wm.verify(postRequestedFor(urlEqualTo("/hook"))
                .withRequestBody(binaryEqualTo(BODY))
                .withHeader("webhook-id", equalTo("evt_1"))
                .withHeader("content-type", equalTo("application/json"))
                .withHeader("user-agent", equalTo("HookRelay/1.0")));
    }
}
