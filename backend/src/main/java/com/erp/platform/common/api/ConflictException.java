package com.erp.platform.common.api;

/**
 * Thrown when a domain rule is violated by an otherwise well-formed request — e.g. a duplicate
 * code within a scope, or setting a default that isn't valid. Mapped to HTTP 409 by
 * {@link GlobalExceptionHandler}. The message is user-safe.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
