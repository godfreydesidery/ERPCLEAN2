package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import jakarta.validation.constraints.NotNull;

/** Request to transition a cheque's status (FR-CASH-11). */
public record ChequeStatusChangeRequest(
        @NotNull ChequeStatus targetStatus
) {}
