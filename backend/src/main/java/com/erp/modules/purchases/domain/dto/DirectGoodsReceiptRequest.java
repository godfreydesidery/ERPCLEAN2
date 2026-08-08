package com.erp.modules.purchases.domain.dto;

import java.util.List;

/**
 * Request DTO for a direct goods receipt — stock received without a prior LPO
 * (K3, Kilimanjaro 2026-08-08).
 *
 * <p>The client sends only what the storekeeper actually knows at the counter: who delivered, in
 * what currency, and the lines. The service auto-raises the backing purchase order behind the
 * scenes and receives against it, so three-way match, GRNI clearing, purchase returns and
 * outstanding tracking all keep working with no special cases.
 *
 * <p>{@code companyUid} in the body (convention §4b / ADR-0007 D-12); the branch comes from
 * {@code RequestContext} (X-Branch-Uid / default branch), never the body. {@code currency} is
 * optional — blank falls back to the company's base currency.
 */
public record DirectGoodsReceiptRequest(
        String companyUid,
        String supplierUid,
        /** Optional ISO-4217 code; blank/null → the company's base currency. */
        String currency,
        /** Free text carried onto BOTH the auto-raised PO and the receipt (e.g. the supplier's delivery note ref). */
        String notes,
        List<DirectGoodsReceiptLineRequest> lines
) {}
