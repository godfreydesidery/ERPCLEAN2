package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.TabularRenderModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RPT-05: an export's header states the branches the figures really cover. */
class BranchScopeHeaderTest {

    private static TabularRenderModel model(List<String> header) {
        return new TabularRenderModel("Report", header, "now", List.of(), List.of(), null,
                List.of(), null);
    }

    @Test
    void replacesTheReportsOwnBranchLine() {
        TabularRenderModel m = model(List.of("Acme Ltd", "From a To b",
                "Branch: All branches (whole company)", "Cashier: All cashiers"));

        TabularRenderModel out = BranchScopeHeader.withScopeLine(m,
                "Branches: Arusha, Moshi (your assigned branches)");

        assertThat(out.headerLines()).containsExactly("Acme Ltd", "From a To b",
                "Branches: Arusha, Moshi (your assigned branches)", "Cashier: All cashiers");
    }

    @Test
    void appendsTheScopeWhenTheReportPrintedNone() {
        TabularRenderModel m = model(List.of("Acme Ltd", "From a To b"));

        TabularRenderModel out = BranchScopeHeader.withScopeLine(m, "Branch: All branches");

        assertThat(out.headerLines()).containsExactly("Acme Ltd", "From a To b",
                "Branch: All branches");
    }

    @Test
    void withoutAGuardTheModelIsUnchanged() {
        TabularRenderModel m = model(List.of("Branch: X"));
        assertThat(BranchScopeHeader.apply(m, null, null)).isSameAs(m);
    }
}
