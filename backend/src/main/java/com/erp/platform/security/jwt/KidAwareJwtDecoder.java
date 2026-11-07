package com.erp.platform.security.jwt;

import com.nimbusds.jwt.JWTParser;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies access JWTs by {@code kid}. A token without {@code kid} (issued before key ids existed)
 * or with the current {@code kid} is verified against the current key; a {@code kid} matching a
 * configured previous key is verified against that key; any other {@code kid} is refused with the
 * same generic {@link BadJwtException} as any other invalid token.
 */
public class KidAwareJwtDecoder implements JwtDecoder {

    private final String currentKid;
    private final JwtDecoder current;
    private final Map<String, JwtDecoder> previous = new HashMap<>();

    public KidAwareJwtDecoder(
            String currentKid, RSAPublicKey currentKey, Map<String, RSAPublicKey> previousKeys) {
        this.currentKid = currentKid;
        this.current = NimbusJwtDecoder.withPublicKey(currentKey).build();
        previousKeys.forEach((kid, key) ->
                previous.put(kid, NimbusJwtDecoder.withPublicKey(key).build()));
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String kid;
        try {
            kid = JWTParser.parse(token).getHeader().toJSONObject().get("kid") instanceof String k
                    ? k : null;
        } catch (ParseException e) {
            throw new BadJwtException("Invalid token");
        }
        if (kid == null || kid.equals(currentKid)) {
            return current.decode(token);
        }
        JwtDecoder decoder = previous.get(kid);
        if (decoder == null) {
            throw new BadJwtException("Invalid token");
        }
        return decoder.decode(token);
    }
}
