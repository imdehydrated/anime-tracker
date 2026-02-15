package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/** 403 - authenticated user is not allowed to perform this action. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
