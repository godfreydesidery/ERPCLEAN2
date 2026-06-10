package com.erp.api;

import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.service.WhtRegisterService;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WHT period register query (ADR-0017 D-9, FR-WHT-04).
 * Accepts either year+month or explicit periodStart+periodEnd date range.
 * Permission seeded in V14: WHT.VIEW.
 */
@RestController
@RequestMapping("/api/v1/wht/register")
public class WhtRegisterController {

    private final WhtRegisterService service;

    public WhtRegisterController(WhtRegisterService service) {
        this.service = service;
    }

    /**
     * Get the WHT register for a company in a period.
     * Accepts year+month (resolved to first/last of month) OR explicit periodStart+periodEnd.
     * If year+month provided, periodStart/periodEnd are ignored.
     */
    @GetMapping
    @PreAuthorize("@perm.has('WHT.VIEW')")
    public WhtRegisterDto getRegister(
            @RequestParam Long companyId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        LocalDate start;
        LocalDate end;
        if (year != null && month != null) {
            YearMonth ym = YearMonth.of(year, month);
            start = ym.atDay(1);
            end   = ym.atEndOfMonth();
        } else if (periodStart != null && periodEnd != null) {
            start = periodStart;
            end   = periodEnd;
        } else {
            throw new IllegalArgumentException(
                    "Provide either year+month or periodStart+periodEnd.");
        }
        return service.getRegister(companyId, start, end);
    }
}
