package com.erp.modules.ap.domain.dto;

/** Accepts an over-tolerance variance on a specific bill line (AP.BILL.MATCH permission). */
public record AcceptVarianceRequest(
        /** uid of the supplier_bill_line whose match variance is being accepted. */
        String billLineUid
) {}
