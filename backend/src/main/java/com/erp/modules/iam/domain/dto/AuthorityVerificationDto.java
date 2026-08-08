package com.erp.modules.iam.domain.dto;

/**
 * Outcome of a manager step-up check (see {@link VerifyAuthorityRequest}).
 *
 * <p>Deliberately carries NO token material of any kind: step-up verifies a credential, it does not
 * establish a session. The calling operator keeps the session they already had.
 *
 * <p>When {@code authorised} is {@code false} every authoriser field is {@code null} — the caller
 * learns nothing about who does or does not exist. When it is {@code true} the authoriser's uid,
 * username and display name are returned so the caller can stamp the override ("Approved by …") and
 * carry the uid into its own audit detail.
 */
public record AuthorityVerificationDto(
        boolean authorised,
        String permissionCode,
        String authoriserUid,
        String authoriserUsername,
        String authoriserName,
        String message) {

    /** Authorisation granted by the named user. */
    public static AuthorityVerificationDto granted(String permissionCode,
                                                   String authoriserUid,
                                                   String authoriserUsername,
                                                   String authoriserName) {
        return new AuthorityVerificationDto(
                true, permissionCode, authoriserUid, authoriserUsername, authoriserName,
                "Authorised by " + authoriserName + ".");
    }

    /** Authorisation refused. {@code message} is a friendly, non-disclosing explanation. */
    public static AuthorityVerificationDto denied(String permissionCode, String message) {
        return new AuthorityVerificationDto(false, permissionCode, null, null, null, message);
    }
}
