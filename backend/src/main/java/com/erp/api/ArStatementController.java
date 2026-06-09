package com.erp.api;

import com.erp.modules.ar.domain.dto.ArAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.ar.service.ArAgeingQuery;
import com.erp.modules.ar.service.ArBalanceService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR customer statement, ageing, and balance read endpoints (ADR-0014, FR-AR-08/12/19).
 * All computed on demand — not stored. companyId and customerId are Long ids.
 * Permission codes seeded in V11__accounts_receivable.sql: AR.STATEMENT.VIEW, AR.VIEW.
 */
@RestController
@RequestMapping("/api/v1/ar")
public class ArStatementController {

    private final ArAgeingQuery ageingQuery;
    private final ArBalanceService balanceService;

    public ArStatementController(ArAgeingQuery ageingQuery, ArBalanceService balanceService) {
        this.ageingQuery    = ageingQuery;
        this.balanceService = balanceService;
    }

    /**
     * Full customer statement (open items + ageing + recent receipts) as at a given date.
     * Defaults to today when {@code asAt} is omitted.
     */
    @GetMapping("/statement")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW')")
    public ArStatementDto statement(
            @RequestParam Long companyId,
            @RequestParam Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.statement(companyId, customerId,
                asAt != null ? asAt : LocalDate.now());
    }

    /**
     * Ageing breakdown by bucket (CURRENT / 1-30 / 31-60 / 61-90 / 90+) as at a given date.
     */
    @GetMapping("/ageing")
    @PreAuthorize("@perm.has('AR.STATEMENT.VIEW')")
    public List<ArAgeingRowDto> ageing(
            @RequestParam Long companyId,
            @RequestParam Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.ageing(companyId, customerId,
                asAt != null ? asAt : LocalDate.now());
    }

    /**
     * Current AR balance (outstanding − unallocated) for a customer.
     * Used by the Sales module at invoice finalise for the credit-limit check (FR-AR-19).
     */
    @GetMapping("/balance")
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ArBalanceDto balance(
            @RequestParam Long companyId,
            @RequestParam Long customerId) {
        return balanceService.currentBalance(companyId, customerId);
    }
}
