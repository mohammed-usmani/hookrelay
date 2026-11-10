package dev.hookrelay.server.common;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** One page of a list response; {@code nextCursor} is null on the last page. */
public record CursorPage<T>(List<T> data, String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("invalid-limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    /**
     * Builds a page from rows fetched with {@code limit + 1}: the extra row only tells us
     * whether another page exists.
     */
    public static <R, T> CursorPage<T> of(List<R> rows, int limit, Function<R, Instant> createdAt,
                                          Function<R, UUID> id, Function<R, T> mapper) {
        boolean more = rows.size() > limit;
        List<R> page = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            R last = page.get(page.size() - 1);
            next = Cursor.encode(createdAt.apply(last), id.apply(last));
        }
        return new CursorPage<>(page.stream().map(mapper).toList(), next);
    }
}
