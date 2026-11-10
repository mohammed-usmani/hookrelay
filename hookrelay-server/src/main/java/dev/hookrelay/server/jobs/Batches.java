package dev.hookrelay.server.jobs;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** Work through a table in small transactions so no job holds locks for long. */
final class Batches {

    static final int SIZE = 1_000;

    private Batches() { }

    /** Repeats a batch statement until it touches no rows; returns the total. */
    static long untilZero(IntSupplier batch) {
        long total = 0;
        int n;
        do {
            n = batch.getAsInt();
            total += n;
        } while (n > 0);
        return total;
    }

    /** Fetches ids and handles each one; stops when a batch is empty or changes nothing. */
    static long forEachId(Function<Integer, List<UUID>> fetch, Predicate<UUID> handle) {
        long total = 0;
        while (true) {
            List<UUID> ids = fetch.apply(SIZE);
            long changed = ids.stream().filter(handle).count();
            total += changed;
            if (ids.size() < SIZE || changed == 0) {
                return total;
            }
        }
    }
}
