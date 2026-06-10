package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the WHT period register (ADR-0017 D-9).
 */
public record WhtRegisterRowDto(
        String whtNumber,
        WhtKind kind,
        String partyKind,
        String partyName,
        String sourceRef,
        BigDecimal taxableBase,
        BigDecimal whtAmount,
        LocalDate certificateDate
) {}
