package com.erp.platform.common.api;

/**
 * Thrown when an entity addressed by uid does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}. The message is user-safe (no internal detail).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, String uid) {
        return new NotFoundException(entity + " not found: " + uid);
    }
}
