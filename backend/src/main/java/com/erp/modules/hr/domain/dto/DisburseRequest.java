package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** Request to disburse net wages or a statutory payable via Cash & Bank (ADR-0032 D-9). */
public record DisburseRequest(
        @NotBlank String cashBankAccountUid,
        LocalDate txnDate
) {}
