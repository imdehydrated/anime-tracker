package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/** 409 - request conflicts with current server state (e.g., duplicates). */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
