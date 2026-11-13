package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.subscription.domain.enums.PlanModule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every seeded permission code must sit in the plan module its area is sold as (ADR-0069 Appendix
 * B). {@link PlanModule#of} falls back to CORE for an unmatched code, so without this test a new
 * code in, say, the GL group would silently become free on every tier.
 */
@DisplayName("every permission code in a sold area is classified into a plan module")
class PlanModuleCoverageTest {

    private static final String SEED = "/db/migration/R__seed_permissions.sql";
    private static final Pattern ROW = Pattern.compile("\\('([A-Z_.]+)',\\s*'([a-z_]+)'");

    /** Permission groups whose codes are sold above Basic (or as an add-on). */
    private static final Set<String> SOLD_GROUPS = Set.of("ap", "approvals", "ar", "bi",
            "budgeting", "cashbank", "costing", "crm", "fixedassets", "fx", "gl", "hr",
            "manufacturing", "projects", "purchases", "routes", "tax");

    /** Codes in a sold group that are deliberately CORE (owner-approved calls, ADR-0069). */
    private static final Set<String> CORE_BY_DECISION = Set.of(
            "PURCHASE.RECEIVE.DIRECT", "PURCHASE.GOODS_RECEIPT.VIEW", "CURRENCY.VIEW");

    @Test
    @DisplayName("no code in a sold group falls through to CORE by accident")
    void soldGroupsAreClassified() throws IOException {
        Map<String, String> seeded = seededCodes();
        assertThat(seeded).as("seed parsed").hasSizeGreaterThan(200);
        List<String> leaked = new ArrayList<>();
        seeded.forEach((code, group) -> {
            if (SOLD_GROUPS.contains(group) && PlanModule.of(code) == PlanModule.CORE
                    && !CORE_BY_DECISION.contains(code)) {
                leaked.add(code + " (" + group + ")");
            }
        });
        assertThat(leaked)
                .as("classify these in PlanModule (or add them to CORE_BY_DECISION with a reason)")
                .isEmpty();
    }

    @Test
    @DisplayName("restaurant codes, wherever they are grouped, are the Restaurant add-on")
    void restaurantCodesAreTheAddOn() throws IOException {
        seededCodes().keySet().stream().filter(c -> c.startsWith("RESTAURANT."))
                .forEach(c -> assertThat(PlanModule.of(c)).as(c).isEqualTo(PlanModule.RESTAURANT));
    }

    private static Map<String, String> seededCodes() throws IOException {
        try (InputStream in = PlanModuleCoverageTest.class.getResourceAsStream(SEED)) {
            assertThat(in).as(SEED + " on the classpath").isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> codes = new LinkedHashMap<>();
            Matcher m = ROW.matcher(sql);
            while (m.find()) {
                codes.putIfAbsent(m.group(1), m.group(2));
            }
            return codes;
        }
    }
}
