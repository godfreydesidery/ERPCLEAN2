package com.erp.modules.ar.domain.dto;

import java.time.LocalDate;

/** Request to write off an uncollectable open item (FR-AR-13). */
public record WriteOffRequest(
        /** The AR open item uid to write off. */
        String arInvoiceUid,
        LocalDate writeOffDate,
        String reason
) {}
