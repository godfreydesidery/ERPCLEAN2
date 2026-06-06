package com.erp.platform.common.api;

/**
 * Thrown when an authenticated caller lacks permission, or acts outside their active scope (e.g. on
 * a company other than the one their session is in). Mapped to HTTP 403 by
 * {@link GlobalExceptionHandler}. The message is user-safe and never names the missing permission
 * code or the scope mismatch detail (no enumeration of what they'd need).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }

    public static ForbiddenException notPermitted() {
        return new ForbiddenException("You do not have permission to perform this action.");
    }
}
