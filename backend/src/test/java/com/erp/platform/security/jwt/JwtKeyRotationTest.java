package com.erp.platform.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.security.config.JwtProperties;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class JwtKeyRotationTest {

    @TempDir
    Path dir;

    private KeyPair current;
    private KeyPair old;
    private KeyPair stranger;

    @BeforeEach
    void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        current = gen.generateKeyPair();
        old = gen.generateKeyPair();
        stranger = gen.generateKeyPair();
    }

    private Path pem(String name, String type, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return Files.writeString(dir.resolve(name),
                "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }

    private RsaKeyProvider provider(boolean withPrevious) throws Exception {
        Path priv = pem("private.pem", "PRIVATE KEY", current.getPrivate().getEncoded());
        Path pub = pem("public.pem", "PUBLIC KEY", current.getPublic().getEncoded());
        List<String> prev = withPrevious
                ? List.of(pem("old.pem", "PUBLIC KEY", old.getPublic().getEncoded()).toString())
                : List.of();
        return new RsaKeyProvider(new JwtProperties(
                "file", priv.toString(), pub.toString(), 15, 7, "erp-api", prev));
    }

    private static String kidOf(KeyPair kp) {
        return RsaKeyProvider.thumbprint((RSAPublicKey) kp.getPublic());
    }

    private String sign(KeyPair kp, String kid) throws Exception {
        JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT);
        if (kid != null) {
            h.keyID(kid);
        }
        SignedJWT jwt = new SignedJWT(h.build(), new JWTClaimsSet.Builder()
                .subject("1").issuer("erp-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
        return jwt.serialize();
    }

    private JwtDecoder decoder(RsaKeyProvider p) {
        return new JwtBeans().jwtDecoder(p);
    }

    @Test
    void currentKeyTokenVerifiesAndCarriesKid() throws Exception {
        RsaKeyProvider p = provider(false);
        JwtEncoder enc = new JwtBeans().jwtEncoder(p);
        Jwt issued = enc.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .subject("1").issuer("erp-api")
                .expiresAt(Instant.now().plusSeconds(600)).build()));
        assertThat(issued.getHeaders()).containsEntry("kid", kidOf(current));
        assertThat(decoder(p).decode(issued.getTokenValue()).getSubject()).isEqualTo("1");
    }

    @Test
    void previousKeyTokenVerifies() throws Exception {
        RsaKeyProvider p = provider(true);
        assertThat(decoder(p).decode(sign(old, kidOf(old))).getSubject()).isEqualTo("1");
    }

    @Test
    void unknownKeyTokenIsRefused() throws Exception {
        RsaKeyProvider p = provider(true);
        assertThatThrownBy(() -> decoder(p).decode(sign(stranger, kidOf(stranger))))
                .isInstanceOf(BadJwtException.class);
        // Spoofing a known kid with the wrong key must fail signature verification too.
        assertThatThrownBy(() -> decoder(p).decode(sign(stranger, kidOf(old))))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void previousKeyNotConfiguredIsRefused() throws Exception {
        RsaKeyProvider p = provider(false);
        assertThatThrownBy(() -> decoder(p).decode(sign(old, kidOf(old))))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void legacyTokenWithoutKidVerifiesAgainstCurrentKey() throws Exception {
        RsaKeyProvider p = provider(true);
        assertThat(decoder(p).decode(sign(current, null)).getSubject()).isEqualTo("1");
        // ...but a kid-less token from another key is not accepted.
        assertThatThrownBy(() -> decoder(p).decode(sign(old, null)))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokensAreNeverSignedWithAPreviousKey() throws Exception {
        RsaKeyProvider p = provider(true);
        Jwt issued = new JwtBeans().jwtEncoder(p).encode(JwtEncoderParameters.from(
                JwtClaimsSet.builder().subject("1").issuer("erp-api")
                        .expiresAt(Instant.now().plusSeconds(600)).build()));
        assertThat(issued.getHeaders().get("kid")).isEqualTo(kidOf(current)).isNotEqualTo(kidOf(old));
        assertThat(p.previousPublicKeys()).containsOnlyKeys(kidOf(old));
        // Verifies with the current public key alone, i.e. it was signed by the current private key.
        assertThat(decoder(provider(false)).decode(issued.getTokenValue())).isNotNull();
    }

    @Test
    void malformedTokenIsRefusedGenerically() throws Exception {
        assertThatThrownBy(() -> decoder(provider(false)).decode("not-a-jwt"))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void missingKeyFileFailsFastWithActionableMessage() {
        JwtProperties props = new JwtProperties("file", dir.resolve("nope.pem").toString(),
                dir.resolve("nope2.pem").toString(), 15, 7, "erp-api", null);
        assertThatThrownBy(() -> new RsaKeyProvider(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void mismatchedPairFailsFast() throws Exception {
        Path priv = pem("private.pem", "PRIVATE KEY", current.getPrivate().getEncoded());
        Path pub = pem("public.pem", "PUBLIC KEY", old.getPublic().getEncoded());
        JwtProperties props = new JwtProperties(
                "file", priv.toString(), pub.toString(), 15, 7, "erp-api", List.of());
        assertThatThrownBy(() -> new RsaKeyProvider(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a pair");
    }
}
