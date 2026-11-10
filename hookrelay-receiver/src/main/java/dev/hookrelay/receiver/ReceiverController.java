package dev.hookrelay.receiver;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A webhook consumer done right (verify, dedupe), with knobs to make it fail on demand. */
@RestController
class ReceiverController {

    private static final Logger log = LoggerFactory.getLogger(ReceiverController.class);
    private static final int KEEP = 100;

    record Received(String webhookId, String eventType, boolean verified, boolean duplicate, Instant at) { }

    record Outcome(boolean verified, boolean duplicate, String error) { }

    record SecretBody(String secret) { }

    private final ChaosSettings settings;
    private final Clock clock;
    private final Deque<Received> recent = new ArrayDeque<>();
    // ponytail: dedupe set grows forever; fine for a demo receiver, a real one keeps ids for the retry window
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    ReceiverController(ChaosSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @PostMapping("/webhooks")
    ResponseEntity<Outcome> receive(@RequestHeader HttpHeaders headers, @RequestBody byte[] body)
            throws InterruptedException {
        ChaosSettings.Chaos chaos = settings.chaos();
        if (chaos.addedLatencyMs() > 0) {
            Thread.sleep(chaos.addedLatencyMs());
        }
        if (chaos.forceStatus() != null) {
            return ResponseEntity.status(chaos.forceStatus()).body(new Outcome(false, false, "chaos: forced status"));
        }
        if (ThreadLocalRandom.current().nextDouble() < chaos.failureRate()) {
            return ResponseEntity.status(500).body(new Outcome(false, false, "chaos: random failure"));
        }

        Map<String, String> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.getFirst()));
        String id = h.get("webhook-id");
        String type = h.get("hookrelay-event-type");

        String error = null;
        WebhookVerifier verifier = settings.verifier(clock);
        if (verifier == null) {
            error = "no secret configured (set WEBHOOK_SECRET or PUT /chaos/secret)";
        } else {
            try {
                verifier.verify(h, body);
            } catch (WebhookVerificationException e) {
                error = e.getMessage();
            }
        }
        boolean verified = error == null;
        boolean duplicate = verified && id != null && !seen.add(id);
        record(new Received(id, type, verified, duplicate, clock.instant()));
        log.info("webhook id={} type={} verified={} duplicate={}{}", id, type, verified, duplicate,
                verified ? "" : " error=" + error);
        return ResponseEntity.status(verified ? 200 : 401).body(new Outcome(verified, duplicate, error));
    }

    @GetMapping("/received")
    synchronized List<Received> received() {
        return List.copyOf(recent);
    }

    @PutMapping("/chaos")
    ChaosSettings.Chaos chaos(@RequestBody ChaosSettings.Chaos chaos) {
        settings.chaos(chaos);
        log.info("chaos now {}", chaos);
        return chaos;
    }

    @PutMapping("/chaos/secret")
    void secret(@RequestBody SecretBody body) {
        settings.secret(body.secret());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private synchronized void record(Received r) {
        recent.addFirst(r);
        if (recent.size() > KEEP) {
            recent.removeLast();
        }
    }
}
