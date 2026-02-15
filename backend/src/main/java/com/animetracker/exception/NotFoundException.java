package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/** 404 - requested resource does not exist. */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
