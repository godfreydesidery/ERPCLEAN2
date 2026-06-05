package com.erp.platform.security.auth;

/**
 * Thrown for any authentication failure (bad credentials, locked, disabled, invalid/expired
 * refresh token). Mapped to HTTP 401 with a GENERIC message — it must never reveal which factor
 * failed (username vs password vs locked), to avoid user enumeration.
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }

    /** The single generic message for credential failures. */
    public static AuthenticationException invalidCredentials() {
        return new AuthenticationException("Invalid username or password.");
    }
}
