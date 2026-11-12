package com.erp.platform.security.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT configuration (bound from {@code erp.jwt.*}). Signing mode is {@code dev-in-memory} (ephemeral
 * RSA key, rotates per restart) or {@code file} (stable RS256 key from a secret store — required for
 * production).
 */
@ConfigurationProperties(prefix = "erp.jwt")
public record JwtProperties(
        String signingMode,
        String privateKeyLocation,
        String publicKeyLocation,
        int accessTokenTtlMinutes,
        int refreshTokenTtlDays,
        String issuer,
        List<String> previousPublicKeyPaths) {

    /** Normalises the optional rotation-overlap list: null/blank entries dropped. */
    public JwtProperties {
        previousPublicKeyPaths = previousPublicKeyPaths == null
                ? List.of()
                : previousPublicKeyPaths.stream()
                        .map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    public boolean isDevInMemory() {
        return signingMode == null || "dev-in-memory".equalsIgnoreCase(signingMode);
    }
}
