package com.erp.modules.bi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.bi.domain.dto.BiHeaderDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.reporting.export.StatementRenderModel.RowType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An exported dashboard must state which branch it covers (UAT, 2026-08).
 *
 * <p>The dashboard accepts a {@code branchId}, and neither the payload nor the download said whether
 * the filter had been honoured. A file outlives the screen that produced it, so the scope has to be
 * printed ON it — including the "no filter" case, which is stated in words rather than left to be
 * inferred from an absent line.
 */
class BiExportFlattenerBranchScopeTest {

    private final BiExportFlattener flattener = new BiExportFlattener();

    @Test
    void exportedDashboard_namesTheBranchItWasFilteredTo() {
        StatementRenderModel model = flattener.flatten(dashboard("Kilimanjaro"));

        assertThat(bannerLabels(model)).contains("Branch: Kilimanjaro");
    }

    @Test
    void exportedDashboard_saysAllBranchesWhenNothingWasFiltered() {
        StatementRenderModel model = flattener.flatten(dashboard("All branches"));

        assertThat(bannerLabels(model))
                .as("silence is the bug — a group-wide export says so out loud")
                .contains("Branch: All branches");
    }

    /**
     * A branch that does not belong to this company must NOT read as "All branches": the filter was
     * not honoured and the panels come back empty, so a group-wide claim would make an empty
     * dashboard look like a company with no trade.
     */
    @Test
    void exportedDashboard_unresolvedBranchIsNotReportedAsGroupWide() {
        StatementRenderModel model = flattener.flatten(dashboard("Unknown branch"));

        assertThat(bannerLabels(model))
                .contains("Branch: Unknown branch")
                .doesNotContain("Branch: All branches");
    }

    // -------------------------------------------------------------------------

    private static List<String> bannerLabels(StatementRenderModel model) {
        return model.rows().stream()
                .filter(r -> r.rowType() == RowType.SECTION_HEADER)
                .map(StatementRenderModel.Row::label)
                .toList();
    }

    private static DashboardDto dashboard(String branchLabel) {
        BiHeaderDto header = new BiHeaderDto(
                10L, "Tembo Group",
                "BR-UID-KILI", "Kilimanjaro", branchLabel,
                "TZS", "2026-08-01 – 2026-08-12",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), Instant.parse("2026-08-12T06:00:00Z"));
        return new DashboardDto(header, null, null, null, null, null, null, null, List.of());
    }
}
