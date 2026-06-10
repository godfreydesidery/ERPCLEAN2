package com.erp.api;

import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.service.YearEndCloseService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Year-End Close operations (ADR-0019, FR-CLOSE-01..08). Sits under the fiscal-year path alongside
 * {@code FiscalPeriodController} (which opens years + closes/reopens periods); this controller adds
 * the year-level CLOSE / REOPEN. Permission: GL.YEAR.CLOSE (distinct from GL.PERIOD.CLOSE — the year
 * close posts the closing journal that rolls P&amp;L into Retained Earnings).
 */
@RestController
@RequestMapping("/api/v1/gl/periods/fiscal-years")
public class YearEndCloseController {

    private final YearEndCloseService service;

    public YearEndCloseController(YearEndCloseService service) {
        this.service = service;
    }

    /** Close an OPEN fiscal year: post the closing journal, auto-close periods, mark CLOSED. */
    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')")
    public FiscalYearDto close(@PathVariable String uid) {
        return service.closeFiscalYear(uid);
    }

    /** Reopen the most-recently-closed fiscal year: reverse the closing journal, mark OPEN. */
    @PostMapping("/uid/{uid}/reopen")
    @PreAuthorize("@perm.scoped(#uid,'fiscalyear','GL.YEAR.CLOSE')")
    public FiscalYearDto reopen(@PathVariable String uid) {
        return service.reopenFiscalYear(uid);
    }
}
