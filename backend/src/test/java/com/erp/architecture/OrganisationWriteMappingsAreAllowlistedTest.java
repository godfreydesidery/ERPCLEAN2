package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code OrganisationController} may expose exactly three write mappings, each gated on a
 * platform-only permission (ADR-0062 P1-9, re-specified 2026-08-15).
 *
 * <h2>Why this replaces "no write mapping at all"</h2>
 *
 * P1-9 originally asked for a CI assertion that {@code OrganisationController} has <b>no</b> write
 * mapping. That was right while no tenant could be created: an organisation was bootstrap-only, so a
 * POST could only be a mistake. P5-2 then added create, suspend and resume deliberately, which made
 * the rule as written a guard against shipped, intended behaviour.
 *
 * <p>Deleting it would have been the easy move and the wrong one. The accident it prevents is real:
 * an organisation write reachable by a tenant is how one customer edits another's tenancy. So the
 * rule becomes an allowlist — these three, and each must carry a {@code platform}-module code.
 *
 * <h2>Why the code matters as much as the count</h2>
 *
 * {@code ORG.CREATE} and {@code ORG.SUSPEND} live in the {@code platform} permission module, which
 * V102's narrowed {@code ORG_ADMIN} CROSS JOIN excludes — so no tenant administrator can hold them
 * however much authority they have inside their own organisation. Gate a fourth endpoint on
 * {@code ORG.VIEW} (a tenant-level code) and it becomes reachable by every customer. This asserts the
 * gate, not merely its presence.
 */
@DisplayName("organisation write mappings are an allowlist, each platform-gated")
class OrganisationWriteMappingsAreAllowlistedTest {

    private static final Path CONTROLLER =
            Path.of("src", "main", "java", "com", "erp", "api", "OrganisationController.java");

    /** Methods that CREATE or CHANGE an organisation, and the platform code each must require. */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>() {{
        put("createTenant", "ORG.CREATE");
        put("suspend", "ORG.SUSPEND");
        put("resume", "ORG.SUSPEND");
    }};

    /** Codes that must never gate an organisation write — they are held by tenant administrators. */
    private static final java.util.Set<String> TENANT_LEVEL_CODES =
            java.util.Set.of("ORG.VIEW", "COMPANY.VIEW", "COMPANY.MANAGE");

    private static final Pattern WRITE_HANDLER = Pattern.compile(
            "@(PostMapping|PutMapping|PatchMapping|DeleteMapping)[^)]*\\)?[\\s\\S]{0,400}?"
                    + "public\\s+[\\w<>\\[\\], .]+\\s+(\\w+)\\s*\\(");

    private static final Pattern PRE_AUTHORIZE = Pattern.compile("@PreAuthorize\\(\"([^\"]+)\"\\)");

    @Test
    @DisplayName("only the three allowlisted writes exist, and each names a platform code")
    void writeMappingsAreExactlyTheAllowlist() throws IOException {
        String src = Files.readString(CONTROLLER, StandardCharsets.UTF_8);

        Map<String, String> found = new LinkedHashMap<>();
        Matcher m = WRITE_HANDLER.matcher(src);
        while (m.find()) {
            String method = m.group(2);
            // The @PreAuthorize nearest above the handler is the one that guards it.
            String before = src.substring(0, m.start(2));
            Matcher g = PRE_AUTHORIZE.matcher(before);
            String gate = null;
            while (g.find()) {
                gate = g.group(1);
            }
            found.put(method, gate);
        }

        assertThat(found.keySet())
                .as("a write mapping on OrganisationController that is not create/suspend/resume is "
                        + "how one customer reaches another's tenancy. If it is intended, add it "
                        + "here WITH its platform code — do not delete this test.")
                .containsExactlyInAnyOrderElementsOf(ALLOWED.keySet());

        found.forEach((method, gate) -> {
            assertThat(gate)
                    .as("%s has no @PreAuthorize — an ungated organisation write", method)
                    .isNotNull();
            assertThat(gate)
                    .as("%s must be gated on %s, a platform-module code that V102's narrowed "
                            + "CROSS JOIN keeps out of every tenant's ORG_ADMIN", method,
                            ALLOWED.get(method))
                    .contains(ALLOWED.get(method));
            assertThat(TENANT_LEVEL_CODES.stream().anyMatch(gate::contains))
                    .as("%s is gated on a TENANT-level code (%s). Every customer's administrator "
                            + "holds those, so the endpoint would be reachable by all of them.",
                            method, gate)
                    .isFalse();
        });
    }
}
