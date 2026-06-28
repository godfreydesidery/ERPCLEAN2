package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.dto.ScreenReadGapDto;
import com.erp.platform.security.ScreenReadClosureManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoleReadClosureServiceImpl#computeGaps} (ADR-0047 Grant-time validator).
 *
 * <p>No Spring context, no DB — exercises the pure static computation over the REAL manifest
 * loaded from the classpath (main-resources). This guards against both logic regressions and
 * manifest drift: if the manifest changes in a way that breaks these assertions, the test goes red.
 *
 * <p>Test cases (per the ADR-0047 spec):
 * <ol>
 *   <li>Role holds {@code AR.RECEIPT.RECORD} but lacks {@code CUSTOMER.VIEW} and {@code AR.VIEW}
 *       → gap on {@code ar.record-receipt} with exactly those two codes; {@code BRANCH.VIEW}
 *       (gate=scopedOrMember) and {@code WHT.VIEW} (optional) are NOT in the missing list.</li>
 *   <li>Role holds {@code AR.RECEIPT.RECORD} + {@code CUSTOMER.VIEW} + {@code AR.VIEW} → no gap.</li>
 *   <li>Role does NOT hold {@code AR.RECEIPT.RECORD} → {@code ar.record-receipt} screen absent.</li>
 *   <li>Disjunction read ({@code PURCHASE.ORDER.VIEW} in {@code ap.enter-supplier-bill}) is never
 *       listed as missing, even when the role lacks it (disjunction is not closure-bearing).</li>
 * </ol>
 */
class RoleReadClosureServiceTest {

    private static final String MANIFEST_RESOURCE = "/security/screen-read-closure.json";

    /** Real manifest screens loaded once for all tests. */
    private static List<ScreenReadClosureManifest.Screen> manifestScreens;

    @BeforeAll
    static void loadManifest() throws IOException {
        try (InputStream is =
                RoleReadClosureServiceTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Manifest not found at " + MANIFEST_RESOURCE
                                + " — expected at backend/src/main/resources/security/screen-read-closure.json");
            }
            JsonNode root = new ObjectMapper().readTree(is);
            List<ScreenReadClosureManifest.Screen> parsed = new ArrayList<>();
            for (JsonNode s : root.path("screens")) {
                String screenId = s.path("screen").asText();
                String accessPerm = s.path("accessPermission").asText();
                List<ScreenReadClosureManifest.ReadEntry> reads = new ArrayList<>();
                for (JsonNode r : s.path("reads")) {
                    reads.add(new ScreenReadClosureManifest.ReadEntry(
                            r.path("code").asText(),
                            r.path("necessity").asText(),
                            r.path("gate").asText()));
                }
                parsed.add(new ScreenReadClosureManifest.Screen(
                        screenId, accessPerm, Collections.unmodifiableList(reads)));
            }
            manifestScreens = Collections.unmodifiableList(parsed);
        }
    }

    // -------------------------------------------------------------------------
    // (1) Role holds accessPermission but is missing closure-bearing required reads
    // -------------------------------------------------------------------------

    @Test
    void missingRequiredClosureBearingReads_producesGapWithExactCodes() {
        // Role can open the screen but has neither CUSTOMER.VIEW nor AR.VIEW.
        Set<String> grants = Set.of("AR.RECEIPT.RECORD");

        List<ScreenReadGapDto> gaps = RoleReadClosureServiceImpl.computeGaps(grants, manifestScreens);

        List<ScreenReadGapDto> arGaps = gaps.stream()
                .filter(g -> "ar.record-receipt".equals(g.screen()))
                .toList();
        assertThat(arGaps).hasSize(1);

        ScreenReadGapDto gap = arGaps.get(0);
        assertThat(gap.accessPermission()).isEqualTo("AR.RECEIPT.RECORD");
        assertThat(gap.missingReads())
                .as("must contain CUSTOMER.VIEW and AR.VIEW")
                .containsExactlyInAnyOrder("CUSTOMER.VIEW", "AR.VIEW");
        assertThat(gap.missingReads())
                .as("BRANCH.VIEW is scopedOrMember — not closure-bearing, must not be missing")
                .doesNotContain("BRANCH.VIEW");
        assertThat(gap.missingReads())
                .as("WHT.VIEW is optional — must not be missing")
                .doesNotContain("WHT.VIEW");
    }

    // -------------------------------------------------------------------------
    // (2) Role holds accessPermission AND all required closure-bearing reads → no gap
    // -------------------------------------------------------------------------

    @Test
    void allRequiredClosureBearingReadsSatisfied_producesNoGapForThatScreen() {
        Set<String> grants = Set.of("AR.RECEIPT.RECORD", "CUSTOMER.VIEW", "AR.VIEW");

        List<ScreenReadGapDto> gaps = RoleReadClosureServiceImpl.computeGaps(grants, manifestScreens);

        assertThat(gaps.stream().map(ScreenReadGapDto::screen))
                .as("ar.record-receipt must not appear when all required reads are granted")
                .doesNotContain("ar.record-receipt");
    }

    // -------------------------------------------------------------------------
    // (3) Role does NOT hold the accessPermission → screen absent from report
    // -------------------------------------------------------------------------

    @Test
    void roleDoesNotHoldAccessPermission_screenAbsentFromReport() {
        // Role has the reads but NOT the screen's accessPermission.
        Set<String> grants = Set.of("CUSTOMER.VIEW", "AR.VIEW");

        List<ScreenReadGapDto> gaps = RoleReadClosureServiceImpl.computeGaps(grants, manifestScreens);

        assertThat(gaps.stream().map(ScreenReadGapDto::screen))
                .as("ar.record-receipt must be absent when role lacks AR.RECEIPT.RECORD")
                .doesNotContain("ar.record-receipt");
    }

    // -------------------------------------------------------------------------
    // (4) Disjunction read is never reported as missing
    // -------------------------------------------------------------------------

    @Test
    void disjunctionRead_neverReportedAsMissing() {
        // Role can open ap.enter-supplier-bill but lacks PURCHASE.ORDER.VIEW.
        // That read has gate=disjunction — not closure-bearing, must not appear in missing.
        // It DOES lack SUPPLIER.VIEW (gate=has, required) → gap exists but without the disjunction.
        Set<String> grants = Set.of("AP.BILL.ENTER");

        List<ScreenReadGapDto> gaps = RoleReadClosureServiceImpl.computeGaps(grants, manifestScreens);

        List<ScreenReadGapDto> apGaps = gaps.stream()
                .filter(g -> "ap.enter-supplier-bill".equals(g.screen()))
                .toList();
        assertThat(apGaps).hasSize(1);
        assertThat(apGaps.get(0).missingReads())
                .as("PURCHASE.ORDER.VIEW has gate=disjunction — must never appear as missing")
                .doesNotContain("PURCHASE.ORDER.VIEW");
        assertThat(apGaps.get(0).missingReads())
                .as("SUPPLIER.VIEW (gate=has, required) must appear as missing")
                .contains("SUPPLIER.VIEW");
    }

    // -------------------------------------------------------------------------
    // (5) Empty grants → no gaps at all (no accessPermission held)
    // -------------------------------------------------------------------------

    @Test
    void emptyGrants_noGapsReported() {
        List<ScreenReadGapDto> gaps =
                RoleReadClosureServiceImpl.computeGaps(Set.of(), manifestScreens);
        assertThat(gaps).isEmpty();
    }
}
