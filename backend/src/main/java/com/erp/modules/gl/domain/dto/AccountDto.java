package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;
import com.erp.modules.gl.domain.enums.NormalBalance;

/** Response DTO for a chart-of-accounts entry (ADR-0013 D-1, D-1 control_type ADR-0040). */
public record AccountDto(
        Long id,
        String uid,
        Long companyId,
        String accountCode,
        String name,
        AccountType accountType,
        NormalBalance normalBalance,
        boolean active,
        boolean allowManualPosting,
        String status,
        /** NULL means an ordinary account; non-null identifies the owning sub-ledger (D-1). */
        ControlType controlType,
        /** ISO-4217 alpha-3 currency lock (P2-M1). NULL = multi-currency allowed. */
        String currency,
        /** P2-D4 (ADR-0041): MANUAL journal lines to this account must carry a cost-centre value. */
        boolean requireCostCentre,
        /** P2-D4 (ADR-0041): MANUAL journal lines to this account must carry a department value. */
        boolean requireDepartment,
        /** P2-D4 (ADR-0041): MANUAL journal lines to this account must carry a project value. */
        boolean requireProject
) {}
