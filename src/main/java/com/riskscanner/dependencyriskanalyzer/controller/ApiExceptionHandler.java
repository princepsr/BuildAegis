package com.riskscanner.dependencyriskanalyzer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns exceptions into small JSON errors, {@code {"error": "..."}}, so that the UI can show a useful message
 * while the server never returns stack traces, exception class names or API keys.
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} / {@link IllegalStateException}: 400 (bad input or missing configuration)</li>
 *   <li>Spring's own request errors (unsupported media type, 406 Not Acceptable, malformed JSON, wrong HTTP method,
 *   missing parameter, ...): keep their proper status, only the body is reformatted</li>
 *   <li>Anything else: 500 with a single-line, truncated and redacted message</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final int MAX_MESSAGE_LENGTH = 300;

    /** Things that look like API keys or bearer tokens, in case a provider echoes them back in an error. */
    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(sk-[A-Za-z0-9_\\-]{8,}|AIza[A-Za-z0-9_\\-]{8,}|(?i:bearer)\\s+[A-Za-z0-9_\\-.=]{8,}|(?i:api[_-]?key[\"'=:\\s]+)[A-Za-z0-9_\\-.]{8,})");

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", sanitize(e.getMessage(), "Invalid request")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", sanitize(e.getMessage(), "Internal server error")));
    }

    /**
     * Spring's standard request errors keep their status code; the body becomes {@code {"error": "..."}} like ours.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String detail = body instanceof ProblemDetail problem ? problem.getDetail() : null;
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String fallback = status == null ? "Request failed" : status.getReasonPhrase();
        return new ResponseEntity<>(Map.of("error", sanitize(detail, fallback)), headers, statusCode);
    }

    /** Single line, redacted and length-limited; falls back to {@code fallback} when there is no message. */
    static String sanitize(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        String redacted = SECRET_LIKE.matcher(oneLine).replaceAll("[redacted]");
        return redacted.length() > MAX_MESSAGE_LENGTH ? redacted.substring(0, MAX_MESSAGE_LENGTH) + "..." : redacted;
    }
}
