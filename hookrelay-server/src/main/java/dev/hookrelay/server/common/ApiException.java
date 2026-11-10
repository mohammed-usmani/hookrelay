package dev.hookrelay.server.common;

/** An error that maps straight to an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String slug;

    public ApiException(int status, String slug, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
    }

    public int getStatus() {
        return status;
    }

    public String getSlug() {
        return slug;
    }

    public static ApiException badRequest(String slug, String detail) {
        return new ApiException(400, slug, detail);
    }

    public static ApiException unprocessable(String slug, String detail) {
        return new ApiException(422, slug, detail);
    }
}
