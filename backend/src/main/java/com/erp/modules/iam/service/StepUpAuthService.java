package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;

/**
 * Manager step-up ("supervisor override") verification: prove that a second, more privileged human
 * is standing at the operator's device and consents to a specific action.
 *
 * <p>Contract — and the whole point of this service being separate from {@link AuthService}:
 * <ul>
 *   <li>It issues NO access token and NO refresh token.</li>
 *   <li>It does not create, rotate, consume or revoke a refresh token.</li>
 *   <li>It does not mutate {@code RequestContext} or any other session state.</li>
 * </ul>
 * The cashier who called it is still logged in, in the same branch scope, when it returns. Anything
 * that logs the operator out mid-sale is a defect, not a trade-off.
 */
public interface StepUpAuthService {

    /**
     * Verify the authoriser's password and then whether THAT user may exercise
     * {@code permissionCode} in the CALLER's active company/branch scope.
     *
     * <p>Never throws for a failed verification — a refusal is a normal
     * {@code authorised = false} result so the caller can show a friendly message and let the
     * manager retype. Every attempt (granted or refused) is audited.
     *
     * @param username       the authorising user's username (case-insensitive)
     * @param rawPassword    the authorising user's password
     * @param permissionCode the permission the operator is asking to have authorised
     */
    AuthorityVerificationDto verifyAuthority(String username, String rawPassword,
                                             String permissionCode);

    /**
     * Re-verify, server-side, a uid that a client claims came back from a successful
     * {@link #verifyAuthority} call.
     *
     * <p><b>Why this exists.</b> A step-up that only ever happens in the client is decoration: the
     * password prompt can be skipped by curl, by a second client, or by a modified build. Every
     * action that a step-up is supposed to protect must therefore carry the authoriser's uid on its
     * own request and have the server independently re-resolve it — the pattern
     * {@code DiscountAuthorisationGuard} already uses for over-ceiling discounts.
     *
     * <p>Applies exactly the same three conditions as {@link #verifyAuthority} does after the
     * password check, so the two cannot drift:
     * <ul>
     *   <li>the named user exists, is active and is not locked;</li>
     *   <li>the named user is NOT the caller — an over-the-shoulder approval involves a second
     *       person by definition;</li>
     *   <li>the named user genuinely holds {@code permissionCode} in the target company.</li>
     * </ul>
     *
     * <p>The honest limit, unchanged from the discount guard: a uid on a request is not proof that
     * the password was typed seconds earlier. What it does guarantee is that the approval names a
     * real, second, genuinely-authorised human, and that the name is on the audit record.
     *
     * <p>Never throws for a refusal — it answers {@code authorised = false} with a friendly,
     * non-disclosing message, so the caller decides what to do about it.
     *
     * @param authoriserUid  uid handed back by the step-up (nullable/blank → refused)
     * @param permissionCode the authority the approval is claimed to prove; must be seeded
     * @param companyId      the company the authority must hold in — take it from the LOADED
     *                       document, never from a caller parameter. Null falls back to the
     *                       caller's active company.
     */
    AuthorityVerificationDto verifyAuthoriserUid(String authoriserUid, String permissionCode,
                                                 Long companyId);
}
