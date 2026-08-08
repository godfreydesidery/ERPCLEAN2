package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Manager step-up ("supervisor override") credentials: the authorising user types their normal
 * USERNAME + PASSWORD on the operator's device, plus the permission code the operator is asking
 * them to authorise (e.g. {@code SALES.INVOICE.OVERRIDE}).
 *
 * <p>Verification is credential-only — it never issues a token and never touches the calling
 * operator's session, so the cashier stays logged in mid-sale.
 *
 * <p>Username is matched case-insensitively (stored lowercased), exactly as at login.
 */
public record VerifyAuthorityRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 200) String password,
        @NotBlank @Size(max = 120) String permissionCode) {
}
