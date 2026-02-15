package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/** 401 - caller is not authenticated or token is invalid. */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
