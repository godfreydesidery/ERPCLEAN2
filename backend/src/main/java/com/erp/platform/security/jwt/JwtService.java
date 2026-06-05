package com.erp.platform.security.jwt;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.platform.security.config.JwtProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues access JWTs (ARCHITECTURE §4). Claims: {@code sub}=userId, {@code username}, {@code isRoot},
 * and the active {@code companyId}/{@code branchId}. The full permission set is NOT embedded — it is
 * resolved per request for the active scope (ADR-0001 D-E, Slice 3), so branch switching changes
 * access without re-issuing the token.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, JwtProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    /** Mint a signed access token for the user in the given active scope. */
    public IssuedToken issueAccessToken(AppUser user, Long companyId, Long branchId) {
        Instant now = Instant.now();
        Instant expires = now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(expires)
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("isRoot", user.isRoot());
        if (companyId != null) {
            claims.claim("companyId", String.valueOf(companyId));
        }
        if (branchId != null) {
            claims.claim("branchId", String.valueOf(branchId));
        }

        String token = encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
        return new IssuedToken(token, expires);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
