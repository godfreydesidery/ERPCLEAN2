package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** Request to create a petty-cash imprest fund (ADR-0050 D-7 PR-B). */
public record CreatePettyCashFundRequest(
        @NotBlank String companyUid,
        /** Optional; a caller-chosen code (e.g. "PETTY-HQ"). Auto-generated (PCF-####) when omitted. */
        String code,
        @NotBlank String name,
        /** Optional; app_user uid of the custodian responsible for the float. */
        String custodianUid,
        /** Authorised imprest ceiling; defaults to zero when omitted. */
        BigDecimal floatAmount,
        /** Defaults to the company's base currency when omitted. */
        String currency
) {}
