package com.erp.platform.security.password;

import com.erp.platform.security.config.SecurityProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Enforces the password policy (FR-IAM-08): minimum length, basic complexity (letters + at least
 * one digit), and rejection of obvious common passwords. One place so every set-password path
 * applies the same rule.
 */
@Component
public class PasswordPolicy {

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "12345678", "qwerty123", "admin123",
            "letmein123", "changeme", "welcome1", "passw0rd");

    private final int minLength;

    public PasswordPolicy(SecurityProperties props) {
        this.minLength = props.password().minLength();
    }

    /** @throws WeakPasswordException if the password fails policy. */
    public void validate(String raw) {
        if (raw == null || raw.length() < minLength) {
            throw new WeakPasswordException(
                    "Password must be at least " + minLength + " characters.");
        }
        boolean hasLetter = raw.chars().anyMatch(Character::isLetter);
        boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new WeakPasswordException("Password must contain letters and at least one number.");
        }
        if (COMMON.contains(raw.toLowerCase())) {
            throw new WeakPasswordException("Password is too common; choose a stronger one.");
        }
    }

    public int minLength() {
        return minLength;
    }
}
