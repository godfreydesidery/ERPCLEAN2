package com.erp.platform.security.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Wires Spring Security's {@link JwtEncoder} (signs with the RSA private key) and {@link JwtDecoder}
 * (verifies with the RSA public key) from the {@link RsaKeyProvider}.
 */
@Configuration
public class JwtBeans {

    @Bean
    public JwtEncoder jwtEncoder(RsaKeyProvider keys) {
        // Tokens are only ever signed with the CURRENT key; its kid goes into the JWS header.
        JWK jwk = new RSAKey.Builder(keys.publicKey())
                .privateKey(keys.privateKey())
                .keyID(keys.keyId())
                .build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyProvider keys) {
        return new KidAwareJwtDecoder(keys.keyId(), keys.publicKey(), keys.previousPublicKeys());
    }
}
