package com.erp.platform.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Auth tuning (bound from {@code erp.security.*}): lockout thresholds and password policy. */
@ConfigurationProperties(prefix = "erp.security")
public record SecurityProperties(Lockout lockout, Password password) {

    public record Lockout(int maxFailedAttempts, int lockMinutes) {
    }

    public record Password(int minLength) {
    }
}
