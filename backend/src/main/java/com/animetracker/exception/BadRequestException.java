package com.animetracker.exception;

import org.springframework.http.HttpStatus;

/** 400 - client supplied invalid input. */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
