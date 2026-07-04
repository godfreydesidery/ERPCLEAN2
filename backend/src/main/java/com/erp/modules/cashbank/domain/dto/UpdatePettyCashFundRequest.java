package com.erp.modules.cashbank.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/**
 * Request to update a petty-cash fund's mutable fields (ADR-0050 D-7 PR-B). Partial update — a
 * {@code null} field leaves the current value unchanged; pass an empty string for
 * {@code custodianUid} to explicitly clear the custodian.
 */
public record UpdatePettyCashFundRequest(
        String name,
        String custodianUid,
        BigDecimal floatAmount,
        MasterStatus status
) {}
