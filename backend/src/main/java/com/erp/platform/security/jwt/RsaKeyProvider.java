package com.erp.platform.security.jwt;

import com.erp.platform.security.config.JwtProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
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
 */
@Component
public class RsaKeyProvider {

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public RsaKeyProvider(JwtProperties props) {
        try {
            if (props.isDevInMemory()) {
                KeyPair pair = generateKeyPair();
                this.publicKey = (RSAPublicKey) pair.getPublic();
                this.privateKey = (RSAPrivateKey) pair.getPrivate();
            } else {
                this.publicKey = loadPublicKey(props.publicKeyLocation());
                this.privateKey = loadPrivateKey(props.privateKeyLocation());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise JWT signing keys", e);
        }
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static RSAPublicKey loadPublicKey(String location) throws Exception {
        byte[] der = readPem(location, "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private static RSAPrivateKey loadPrivateKey(String location) throws Exception {
        byte[] der = readPem(location, "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] readPem(String location, String type) throws Exception {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing-mode=file but key location is empty for " + type);
        }
        String pem = Files.readString(Path.of(location))
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }
}
