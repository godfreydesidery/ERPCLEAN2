package com.erp.platform.security.jwt;

import com.erp.platform.security.config.JwtProperties;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Provides the RSA keypair used to sign/verify JWTs.
 *
 * <ul>
 *   <li><b>dev-in-memory</b> (default): generates a fresh 2048-bit keypair at startup. Every restart
 *       rotates the key, invalidating all previously issued tokens — acceptable for dev only.</li>
 *   <li><b>file</b>: loads a stable PEM keypair from the configured locations. REQUIRED for
 *       production so restarts don't log everyone out. (security + devops gating item.)</li>
 * </ul>
 *
 * <p>Every key has a {@code kid}: the RFC 7638 JWK SHA-256 thumbprint of its public key, base64url
 * (43 chars), computed deterministically from the key so all replicas agree. Retired public keys
 * ({@code erp.jwt.previous-public-key-paths}) are loaded for verification only.
 *
 * <p>Any load failure aborts startup with an actionable message in the log (paths and causes, never
 * key material). Nothing here is reachable from an HTTP response.
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;
    private final Map<String, RSAPublicKey> previousKeys = new LinkedHashMap<>();

    public RsaKeyProvider(JwtProperties props) {
        try {
            if (props.isDevInMemory()) {
                KeyPair pair = generateKeyPair();
                this.publicKey = (RSAPublicKey) pair.getPublic();
                this.privateKey = (RSAPrivateKey) pair.getPrivate();
            } else {
                this.publicKey = loadPublicKey(props.publicKeyLocation(), "ERP_JWT_PUBLIC_KEY");
                this.privateKey = loadPrivateKey(props.privateKeyLocation(), "ERP_JWT_PRIVATE_KEY");
                if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                    throw fail("The configured private key and public key are not a pair "
                            + "(modulus mismatch). Check ERP_JWT_PRIVATE_KEY and ERP_JWT_PUBLIC_KEY.", null);
                }
            }
            this.keyId = thumbprint(publicKey);
            for (String path : props.previousPublicKeyPaths()) {
                RSAPublicKey prev = loadPublicKey(path, "ERP_JWT_PREVIOUS_PUBLIC_KEY_PATHS");
                String prevKid = thumbprint(prev);
                if (prevKid.equals(keyId)) {
                    log.warn("JWT previous public key {} is identical to the current key; ignored", path);
                    continue;
                }
                previousKeys.put(prevKid, prev);
            }
        } catch (IllegalStateException e) {
            log.error("JWT signing key setup failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("JWT signing key setup failed: {}", e.toString());
            throw new IllegalStateException(
                    "Failed to initialise JWT signing keys (see log for cause)", e);
        }
        log.info("JWT signing key ready: mode={}, kid={}, previousKeys={}",
                props.isDevInMemory() ? "dev-in-memory" : "file", keyId, previousKeys.keySet());
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    /** {@code kid} of the current signing key. */
    public String keyId() {
        return keyId;
    }

    /** Retired public keys by {@code kid}, accepted for verification only. */
    public Map<String, RSAPublicKey> previousPublicKeys() {
        return Map.copyOf(previousKeys);
    }

    /** RFC 7638 SHA-256 thumbprint of the RSA public key, base64url. */
    static String thumbprint(RSAPublicKey key) {
        try {
            return new RSAKey.Builder(key).build().computeThumbprint().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute JWT key thumbprint", e);
        }
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static RSAPublicKey loadPublicKey(String location, String setting) {
        byte[] der = readPem(location, "PUBLIC KEY", setting);
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw fail("Cannot parse RSA public key at '" + location + "' (expected an X.509 SPKI "
                    + "PEM, as produced by 'openssl pkey -pubout').", e);
        }
    }

    private static RSAPrivateKey loadPrivateKey(String location, String setting) {
        byte[] der = readPem(location, "PRIVATE KEY", setting);
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw fail("Cannot parse RSA private key at '" + location + "' (expected an unencrypted "
                    + "PKCS#8 PEM, 'BEGIN PRIVATE KEY', as produced by 'openssl genpkey').", e);
        }
    }

    private static byte[] readPem(String location, String type, String setting) {
        if (location == null || location.isBlank()) {
            throw fail("JWT signing-mode=file but no path is set for the " + type
                    + ". Set " + setting + " to a mounted PEM file.", null);
        }
        String raw;
        try {
            raw = Files.readString(Path.of(location.trim()));
        } catch (NoSuchFileException e) {
            throw fail("JWT key file not found: '" + location + "' (" + setting
                    + "). Check the secret mount path.", e);
        } catch (AccessDeniedException e) {
            throw fail("JWT key file not readable: '" + location + "' (" + setting
                    + "). Grant the API process user read permission.", e);
        } catch (IOException | RuntimeException e) {
            throw fail("Cannot read JWT key file '" + location + "' (" + setting + "): "
                    + e.getClass().getSimpleName(), e);
        }
        if (!raw.contains("-----BEGIN " + type + "-----")) {
            throw fail("JWT key file '" + location + "' is not a PEM '" + type + "' block.", null);
        }
        try {
            return Base64.getDecoder().decode(raw
                    .replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "")
                    .replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw fail("JWT key file '" + location + "' has corrupt PEM content.", e);
        }
    }

    private static IllegalStateException fail(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
