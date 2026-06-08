package com.erp.api;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.service.TrialBalanceQuery;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trial balance read endpoint (ADR-0013, FR-GL-16).
 * Computed on demand; not stored. Nets to zero on a correct set of books.
 * Permission: GL.VIEW.
 */
@RestController
@RequestMapping("/api/v1/gl/trial-balance")
public class TrialBalanceController {

    private final TrialBalanceQuery query;

    public TrialBalanceController(TrialBalanceQuery query) {
        this.query = query;
    }

    /** Full trial balance for the company (all periods). */
    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public TrialBalanceDto get(@RequestParam Long companyId) {
        return query.compute(companyId);
    }

    /** Trial balance filtered to a single fiscal period. */
    @GetMapping("/period")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public TrialBalanceDto getForPeriod(@RequestParam Long companyId,
                                        @RequestParam Long periodId) {
        return query.computeForPeriod(companyId, periodId);
    }
}
