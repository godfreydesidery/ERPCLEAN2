package com.erp.api;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.service.ApAgeingQuery;
import com.erp.modules.ap.service.ApBalanceService;
import com.erp.modules.ap.service.ApReconciliationQuery;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AP reporting: balance, ageing, reconciliation (ADR-0015 D-7/D-8).
 * All read-only; permission: AP.VIEW.
 */
@RestController
@RequestMapping("/api/v1/ap/statement")
public class ApStatementController {

    private final ApBalanceService      balanceService;
    private final ApAgeingQuery         ageingQuery;
    private final ApReconciliationQuery reconciliationQuery;

    public ApStatementController(ApBalanceService balanceService,
                                  ApAgeingQuery ageingQuery,
                                  ApReconciliationQuery reconciliationQuery) {
        this.balanceService      = balanceService;
        this.ageingQuery         = ageingQuery;
        this.reconciliationQuery = reconciliationQuery;
    }

    /** Current outstanding balance for a supplier. */
    @GetMapping("/balance")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApBalanceDto balance(@RequestParam Long companyId,
                                @RequestParam Long supplierId) {
        return balanceService.currentBalance(companyId, supplierId);
    }

    /** Ageing breakdown as at a given date. */
    @GetMapping("/ageing")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public List<ApAgeingRowDto> ageing(
            @RequestParam Long companyId,
            @RequestParam Long supplierId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt) {
        return ageingQuery.ageing(companyId, supplierId, asAt);
    }

    /** Sub-ledger vs GL 2100 reconciliation. */
    @GetMapping("/reconciliation")
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApReconciliationDto reconcile(@RequestParam Long companyId) {
        return reconciliationQuery.reconcile(companyId);
    }
}
