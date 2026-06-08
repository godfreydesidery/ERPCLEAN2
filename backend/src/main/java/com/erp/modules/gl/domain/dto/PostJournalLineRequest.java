package com.erp.modules.gl.domain.dto;

import java.math.BigDecimal;

/**
 * One line in a PostJournalRequest. Exactly one of debitAmount/creditAmount must be positive;
 * the other zero or null (service-validated, BR-GL-08).
 */
public record PostJournalLineRequest(
        String accountUid,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String lineMemo
) {}
