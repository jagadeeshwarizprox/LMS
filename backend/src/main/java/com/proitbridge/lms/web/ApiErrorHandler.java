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
