package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/**
 * Base exception type for domain-level API failures.
 * Each subclass maps directly to an HTTP status code.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
