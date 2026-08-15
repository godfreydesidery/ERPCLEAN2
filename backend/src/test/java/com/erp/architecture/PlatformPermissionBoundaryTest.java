package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards R-2 (ADR-0062 §1.2, P4-1e): a platform capability must never reach a tenant's own
 * administrator.
 *
 * <h2>Why this is a static read of the SQL rather than a database assertion</h2>
 *
 * The grant is made by {@code R__seed_permissions.sql} at Flyway time, so the only way to observe
 * its effect is to let the seed run — which needs Docker and puts the check in the integration
 * suite, outside the PR gate. The mechanism, though, is one SQL clause, and that clause is exactly
 * what a future edit would delete. Reading it directly puts the guard in the fast suite where a
 * regression is caught by the person who caused it. The *effect* is confirmed separately against a
 * real seeded database on QA after deploy.
 *
 * <h2>Why the platform module must not be empty</h2>
 *
 * {@code AND p.module <> 'platform'} over a catalogue with no platform rows is a no-op, and a test
 * asserting "ORG_ADMIN holds no platform code" would then pass while proving nothing — the same
 * vacuous-pass that made the tenancy parity harness green against two empty result sets before it
 * was made to seed its own fixtures. {@link #thePlatformModuleIsNotEmpty()} exists so this boundary
 * cannot quietly become decorative.
 */
@DisplayName("R-2 · platform capabilities never flow to a tenant admin")
class PlatformPermissionBoundaryTest {

    private static final String SEED = "db/migration/R__seed_permissions.sql";

    /** {@code ('CODE', 'module', 'description')} rows of the permission catalogue. */
    private static final Pattern CATALOGUE_ROW =
            Pattern.compile("\\('([A-Z][A-Z0-9_.]*)',\\s*'([a-z-]+)'");

    private static String seed() throws IOException {
        try (InputStream in = PlatformPermissionBoundaryTest.class.getClassLoader()
                .getResourceAsStream(SEED)) {
            assertThat(in).as("%s must be on the test classpath", SEED).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> moduleByCode() throws IOException {
        Map<String, String> byCode = new LinkedHashMap<>();
        Matcher m = CATALOGUE_ROW.matcher(seed());
        while (m.find()) {
            byCode.put(m.group(1), m.group(2));
        }
        return byCode;
    }

    @Test
    @DisplayName("the ORG_ADMIN grant excludes the platform module")
    void theCrossJoinExcludesPlatform() throws IOException {
        String sql = seed();

        // The clause itself. ORG_ADMIN's grant is a CROSS JOIN over the whole catalogue - it hands
        // that role every code that exists, forever, automatically - so this one line is the entire
        // boundary between a customer's administrator and the platform operator.
        assertThat(sql)
                .as("R-2: without `AND p.module <> 'platform'` in the ORG_ADMIN CROSS JOIN, adding a "
                        + "platform code such as ORG.SUSPEND hands every customer's admin the power "
                        + "to suspend organisations on the next deploy — no review, no migration, "
                        + "just a checksum change")
                .contains("p.module <> 'platform'");

        // ...and it must be the ORG_ADMIN statement it guards, not some other CROSS JOIN added later.
        int join = sql.indexOf("CROSS JOIN permissions p");
        int orgAdmin = sql.indexOf("r.code = 'ORG_ADMIN'", join);
        int exclusion = sql.indexOf("p.module <> 'platform'", orgAdmin);
        assertThat(join).as("the ORG_ADMIN CROSS JOIN must still exist").isNotNegative();
        assertThat(exclusion)
                .as("the exclusion must sit inside the ORG_ADMIN grant, not merely somewhere in the "
                        + "file — a clause that drifted onto another statement guards nothing")
                .isNotNegative();
        assertThat(sql.indexOf(';', orgAdmin))
                .as("the exclusion must fall before the statement terminator")
                .isGreaterThan(exclusion);
    }

    @Test
    @DisplayName("the platform module is not empty, so the exclusion is not decorative")
    void thePlatformModuleIsNotEmpty() throws IOException {
        assertThat(moduleByCode()).containsValue("platform");
    }

    @Test
    @DisplayName("only genuinely platform-operator capabilities carry the platform module")
    void platformModuleHoldsOnlyOperatorCapabilities() throws IOException {
        // A deliberately small, reviewed list. The module is a security discriminator, so growing it
        // must be a decision someone makes on purpose — this assertion is the place they have to
        // come and make it.
        assertThat(moduleByCode().entrySet().stream()
                        .filter(e -> "platform".equals(e.getValue()))
                        .map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("ORG.CREATE", "ORG.SUSPEND");
    }

    @Test
    @DisplayName("ORG.VIEW is a TENANT capability and must keep flowing to ORG_ADMIN")
    void orgViewStaysTenantScoped() throws IOException {
        // The mirror of the rule above, and the one that would break a screen rather than leak a
        // capability: ORG.VIEW gates the organisation list. Mark it 'platform' by mistake and every
        // tenant admin silently loses it on the next deploy.
        assertThat(moduleByCode())
                .containsEntry("ORG.VIEW", "iam")
                .containsEntry("ORG.CREATE", "platform")
                .containsEntry("ORG.SUSPEND", "platform");
    }
}
