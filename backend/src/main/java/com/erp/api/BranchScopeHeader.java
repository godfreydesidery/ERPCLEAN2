package com.erp.api;

import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import java.util.ArrayList;
import java.util.List;

/**
 * RPT-05: makes an exported report state the branches it REALLY covers. A report's own header used
 * to print "Branch: All branches (whole company)" whenever no branch was picked, even for a
 * branch-limited caller whose figures cover only their assigned branches (wave-1 ruling). The true
 * scope comes from {@link BranchReadGuard#scopeHeaderLine}, the same line the sales exports print.
 *
 * <p>The report's existing "Branch: ..." header line is replaced; a report that printed none gets
 * the scope line appended to its header.
 */
final class BranchScopeHeader {

    private static final String BRANCH_PREFIX = "Branch: ";

    private BranchScopeHeader() {}

    /**
     * @param guard     null only where a controller was built by hand (unit tests); the model is
     *                  then returned unchanged
     * @param branchUid the branch filter the export was asked for, or null for "all"
     */
    static TabularRenderModel apply(TabularRenderModel model, BranchReadGuard guard,
                                    String branchUid) {
        if (guard == null || model == null) {
            return model;
        }
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.companyId() == null) {
            return model;
        }
        String scopeLine = guard.scopeHeaderLine(principal.companyId(), branchUid);
        return withScopeLine(model, scopeLine);
    }

    /** Replaces the first "Branch: ..." header line with {@code scopeLine}, or appends it. */
    static TabularRenderModel withScopeLine(TabularRenderModel model, String scopeLine) {
        if (scopeLine == null || scopeLine.isBlank()) {
            return model;
        }
        List<String> lines = new ArrayList<>(
                model.headerLines() != null ? model.headerLines() : List.of());
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) != null && lines.get(i).startsWith(BRANCH_PREFIX)) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            lines.set(at, scopeLine);
        } else {
            lines.add(scopeLine);
        }
        return new TabularRenderModel(model.title(), lines, model.generatedAt(), model.columns(),
                model.rows(), model.totalsRow(), model.footerLines(), model.logoDataUri());
    }
}
