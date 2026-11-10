package dev.hookrelay.server.delivery;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Keeps the first {@code limit} bytes of a response body, then hangs up. */
final class CappedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final byte[] buffer;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private int size;
    private Flow.Subscription subscription;

    CappedBodySubscriber(int limit) {
        this.buffer = new byte[limit];
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) {
            return;
        }
        for (ByteBuffer b : items) {
            int n = Math.min(b.remaining(), buffer.length - size);
            b.get(buffer, size, n);
            size += n;
            if (size == buffer.length) {
                subscription.cancel();
                complete();
                return;
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        complete();
    }

    private void complete() {
        result.complete(java.util.Arrays.copyOf(buffer, size));
    }
}
