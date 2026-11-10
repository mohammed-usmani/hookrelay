package dev.hookrelay.server.delivery;

import com.rabbitmq.client.Channel;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.RabbitTopology;
import dev.hookrelay.server.crypto.WebhookSigner;
import dev.hookrelay.server.endpoint.SsrfGuard;
import dev.hookrelay.server.endpoint.SsrfViolation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes work messages: claim → SSRF re-check → sign → POST → record. Acks only after the
 * result is committed. Unexpected errors nack without requeue (no poison-message loops); the
 * stuck-delivery sweeper picks the delivery up again later.
 */
@Component
class DeliveryWorker {

    static final String LISTENER_ID = "deliveryWorker";
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryStateService state;
    private final WebhookSender sender;
    private final WebhookSigner signer;
    private final SsrfGuard ssrf;
    private final ObjectMapper json;
    private final Clock clock;

    DeliveryWorker(DeliveryStateService state, WebhookSender sender, WebhookSigner signer, SsrfGuard ssrf,
                   ObjectMapper json, Clock clock) {
        this.state = state;
        this.sender = sender;
        this.signer = signer;
        this.ssrf = ssrf;
        this.json = json;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitTopology.WORK_QUEUE, containerFactory = "workerContainerFactory")
    void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            JsonNode body = json.readTree(message.getBody());
            UUID deliveryId = UUID.fromString(body.at("/deliveryId").asString());
            UUID token = UUID.fromString(body.at("/dispatchToken").asString());
            MDC.put("delivery_id", deliveryId.toString());
            Optional<Claim> claim = state.claim(deliveryId, token);
            if (claim.isPresent()) {
                Claim c = claim.get();
                MDC.put("event_id", c.eventId().toString());
                MDC.put("application_id", c.applicationId().toString());
                Instant startedAt = Times.now(clock);
                SendResult result = attempt(c);
                if (state.recordResult(c, startedAt, result)) {
                    int killed = state.killPending(c.endpointId(), "endpoint_disabled");
                    log.warn("Endpoint {} disabled after failing too long; {} pending deliveries dead-lettered",
                            c.endpointId(), killed);
                }
                log.info("Attempt {} for delivery {}: {}", c.attemptNo(), c.deliveryId(), result.outcome());
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("Failed to process work message", e);
            channel.basicNack(tag, false, false);
        } finally {
            MDC.clear();
        }
    }

    private SendResult attempt(Claim c) {
        long start = System.nanoTime();
        URI url = URI.create(c.url());
        try {
            ssrf.checkUrl(url);
            ssrf.resolveAllowed(url.getHost());
        } catch (SsrfViolation v) {
            return WebhookSender.failure(SendResult.Outcome.SSRF_BLOCKED, start, v.getMessage());
        } catch (UncheckedIOException dns) {
            return WebhookSender.failure(SendResult.Outcome.CONNECTION_ERROR, start, "DNS lookup failed");
        }
        byte[] body = c.payload().getBytes(StandardCharsets.UTF_8);
        String id = c.eventId().toString();
        long ts = clock.instant().getEpochSecond();
        Map<String, String> headers = Map.of(
                "webhook-id", id,
                "webhook-timestamp", Long.toString(ts),
                "webhook-signature", signer.sign(id, ts, body, c.secrets()),
                "hookrelay-event-type", c.eventType()); // lets receivers route without parsing the body
        return sender.send(url, headers, body);
    }
}
