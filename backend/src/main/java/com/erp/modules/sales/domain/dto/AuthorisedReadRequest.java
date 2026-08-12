package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body of the manager-authorised X-read / Z-read endpoints.
 *
 * <p><b>{@code authorisedByUid}</b> carries the uid returned by a successful manager step-up
 * ({@code POST /api/v1/auth/verify-authority} with {@code POS.SESSION.RECONCILE}). It exists for the
 * same reason {@code VoidInvoiceRequest} has one: a till-only prompt is decoration — the password
 * dialog can be skipped by curl, by a second client, or by a modified build — so the approval has to
 * travel on the request and be re-resolved server-side.
 *
 * <p><b>It is not a credential and it authorises nothing by itself.</b> No token is minted, stored,
 * or accepted here. The uid names a person; the server independently re-resolves that person against
 * the LOADED session's company and refuses unless they are a real, active, unlocked, second human who
 * genuinely holds the approval permission. Nothing survives the request, so a second report needs a
 * second approval.
 *
 * <p>Optional on the wire: a caller who holds {@code POS.SESSION.VIEW} themselves has nobody to
 * escalate to and leaves the field null (it is then ignored entirely).
 */
public record AuthorisedReadRequest(
        @Size(max = 26) String authorisedByUid
) {

    /** Self-authorised callers (and internal callers) that carry no manager approval. */
    public AuthorisedReadRequest() {
        this(null);
    }
}
