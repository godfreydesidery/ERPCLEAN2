package com.erp.platform.security.password;

/**
 * Thrown when a proposed password fails {@link PasswordPolicy}. Mapped to HTTP 400 by the global
 * handler. The message is user-safe and actionable (states the rule that failed).
 */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
        super(message);
    }
}
