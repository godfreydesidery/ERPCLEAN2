package com.erp.api;

import com.erp.modules.sales.domain.dto.PosDashboardDto;
import com.erp.modules.sales.service.PosDashboardQuery;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The till's sales dashboard (Tulia 2026-11-02): sales per day, payment type and department at the
 * caller's active branch. {@code SALES.INVOICE.VIEW}, like the till's Today's sales; it shows no
 * cost or profit, so it needs no valuation permission.
 */
@RestController
@RequestMapping("/api/v1/pos/dashboard")
public class PosDashboardController {

    private final PosDashboardQuery query;

    public PosDashboardController(PosDashboardQuery query) {
        this.query = query;
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public PosDashboardDto dashboard(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate) {
        return query.report(fromDate, toDate);
    }
}
