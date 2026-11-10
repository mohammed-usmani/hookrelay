package dev.hookrelay.server.delivery;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * Makes one webhook HTTP call. Never follows redirects (a redirect could point anywhere,
 * including internal addresses), caps the whole exchange at the request timeout, and reads
 * at most 4 KB of the response.
 */
public class WebhookSender {

    static final int MAX_BODY_BYTES = 4096;

    private final HttpClient client;
    private final Duration requestTimeout;

    public WebhookSender(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        this.requestTimeout = requestTimeout;
    }

    public SendResult send(URI url, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header("content-type", "application/json")
                .header("user-agent", "HookRelay/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(req::header);

        long start = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> call =
                client.sendAsync(req.build(), info -> new CappedBodySubscriber(MAX_BODY_BYTES));
        try {
            HttpResponse<byte[]> res = call.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            int status = res.statusCode();
            SendResult.Outcome outcome = status >= 200 && status < 300 ? SendResult.Outcome.SUCCESS
                    : status >= 300 && status < 400 ? SendResult.Outcome.REDIRECT
                    : SendResult.Outcome.HTTP_ERROR;
            String snippet = new String(res.body(), StandardCharsets.UTF_8);
            return new SendResult(outcome, status, elapsed(start), snippet,
                    outcome == SendResult.Outcome.SUCCESS ? null : "HTTP " + status);
        } catch (TimeoutException e) {
            call.cancel(true);
            return failure(SendResult.Outcome.TIMEOUT, start, "no response within " + requestTimeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            return classify(e.getCause(), start);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return failure(SendResult.Outcome.CONNECTION_ERROR, start, "interrupted");
        }
    }

    private static SendResult classify(Throwable cause, long start) {
        if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
            return failure(SendResult.Outcome.TIMEOUT, start, cause.getMessage());
        }
        if (cause instanceof SSLException) {
            return failure(SendResult.Outcome.TLS_ERROR, start, cause.getMessage());
        }
        if (cause instanceof ConnectException) {
            return failure(SendResult.Outcome.CONNECTION_ERROR, start, "connection refused");
        }
        return failure(SendResult.Outcome.CONNECTION_ERROR, start, String.valueOf(cause));
    }

    static SendResult failure(SendResult.Outcome outcome, long start, String error) {
        return new SendResult(outcome, null, elapsed(start), null, error);
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
