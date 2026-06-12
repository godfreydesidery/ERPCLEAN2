package com.erp.modules.budgeting.domain.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Departmental actuals view output (ADR-0034 D-8, FR-BUD-17).
 * GL actuals grouped by cost_centre_value × account, no budget join.
 * NULL cost_centre_value_id = Unallocated bucket (OQ-BUD-07).
 */
public record DepartmentalActualsDto(
        Long companyId,
        String fiscalYearUid,
        LocalDate fromDate,
        LocalDate toDate,
        List<DepartmentalActualsRowDto> rows
) {}
