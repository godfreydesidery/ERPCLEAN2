package com.erp.api;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.service.FiscalCalendarService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fiscal year + period management controller (ADR-0013, FR-GL-08/09).
 * Permission codes: GL.VIEW, GL.PERIOD.CLOSE.
 */
@RestController
@RequestMapping("/api/v1/gl/periods")
public class FiscalPeriodController {

    private final FiscalCalendarService service;

    public FiscalPeriodController(FiscalCalendarService service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Fiscal years
    // -------------------------------------------------------------------------

    @PostMapping("/fiscal-years")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','GL.PERIOD.CLOSE')")
    public FiscalYearDto openFiscalYear(@Valid @RequestBody OpenFiscalYearRequest req) {
        return service.openFiscalYear(req);
    }

    @GetMapping("/fiscal-years/uid/{uid}")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public FiscalYearDto getFiscalYear(@PathVariable String uid) {
        return service.getFiscalYearByUid(uid);
    }

    @GetMapping("/fiscal-years")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public List<FiscalYearDto> listFiscalYears(@RequestParam Long companyId) {
        return service.listFiscalYears(companyId);
    }

    // -------------------------------------------------------------------------
    // Fiscal periods
    // -------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public List<FiscalPeriodDto> listPeriods(@RequestParam Long companyId) {
        return service.listPeriods(companyId);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public FiscalPeriodDto getPeriod(@PathVariable String uid) {
        return service.getPeriodByUid(uid);
    }

    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid,'fiscalperiod','GL.PERIOD.CLOSE')")
    public FiscalPeriodDto closePeriod(@PathVariable String uid) {
        return service.closePeriod(uid);
    }

    @PostMapping("/uid/{uid}/reopen")
    @PreAuthorize("@perm.scoped(#uid,'fiscalperiod','GL.PERIOD.CLOSE')")
    public FiscalPeriodDto reopenPeriod(@PathVariable String uid) {
        return service.reopenPeriod(uid);
    }
}
