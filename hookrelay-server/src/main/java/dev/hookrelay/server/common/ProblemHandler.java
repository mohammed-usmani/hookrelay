package dev.hookrelay.server.common;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every error into RFC 9457 problem JSON with a stable {@code type} URI. */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProblemHandler.class);

    public static final String TYPE_BASE = "https://hookrelay.dev/problems/";

    public static URI type(String slug) {
        return URI.create(TYPE_BASE + slug);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.getStatus()), e.getMessage());
        pd.setType(type(e.getSlug()));
        e.getProperties().forEach(pd::setProperty);
        return ResponseEntity.status(e.getStatus()).body(pd);
    }

    /** Anything unexpected: log it with the stack trace, tell the caller nothing internal. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        pd.setType(type("internal-error"));
        return ResponseEntity.internalServerError().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setType(type("validation"));
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
        pd.setType(type("malformed-request"));
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd && (pd.getType() == null || "about:blank".equals(pd.getType().toString()))) {
            pd.setType(type(slugFor(statusCode)));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String slugFor(HttpStatusCode code) {
        HttpStatus s = HttpStatus.resolve(code.value());
        return s == null ? "error" : s.name().toLowerCase().replace('_', '-');
    }
}
