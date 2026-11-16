package com.proitbridge.lms.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Errors say what happened and what to do next. They do not apologise. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handled(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? "Request failed." : e.getReason()));
    }

    /**
     * An upload past Spring's multipart ceiling is refused before any controller runs,
     * so FileService never gets to say what the limit is. It reached the screen as
     * "Something went wrong on our side (MaxUploadSizeExceededException: Maximum upload
     * size exceeded)", which names the exception and not the fix. This says both, as the
     * 413 it is, so the upload control can show it against the file that caused it.
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        long max = e.getMaxUploadSize();
        String limit = max > 0 ? (max / (1024 * 1024)) + " MB" : "the configured limit";
        return ResponseEntity.status(413)
                .body(Map.of("error", "That file is larger than " + limit
                        + ", which is the most this server accepts in one upload. "
                        + "Compress it, trim it, or ask the office to raise UPLOAD_MAX_FILE_SIZE."));
    }

    /**
     * An unexpected failure used to vanish: no log line was written anywhere, and the
     * only thing the client showed was "Something went wrong on our side". A mentor
     * reporting a blank screen gave nobody a way to find out why.
     *
     * The stack trace now reaches the server log with the path that caused it, and the
     * reply carries the exception type and message so the same information is visible
     * without shell access to the server.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled failure on {} {}", req.getMethod(), req.getRequestURI(), e);
        String type = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? type : type + ": " + e.getMessage();
        return ResponseEntity.status(500)
                .body(Map.of("error", "Something went wrong on our side.",
                        "detail", message,
                        "path", String.valueOf(req.getRequestURI())));
    }
}
