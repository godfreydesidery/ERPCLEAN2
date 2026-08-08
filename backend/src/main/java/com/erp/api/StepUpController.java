package com.erp.api;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.domain.dto.VerifyAuthorityRequest;
import com.erp.modules.iam.service.StepUpAuthService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manager step-up ("supervisor override") verification.
 *
 * <p>Kept out of {@code AuthController} on purpose: everything there mints or revokes session
 * tokens, and this resource must never do either. A supervisor walks to the cashier's device, types
 * their own username + password, and the operator's session is untouched — same user, same branch,
 * same tokens, before and after.
 *
 * <p>Gated on authentication ONLY, not on a permission: the caller is the junior operator asking for
 * an override, so requiring the override permission of the caller would defeat the purpose. The real
 * check is inside — the AUTHORISER's password and the AUTHORISER's permission. Every attempt is
 * audited with the operator as actor.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class StepUpController {

    private final StepUpAuthService stepUp;

    public StepUpController(StepUpAuthService stepUp) {
        this.stepUp = stepUp;
    }

    /**
     * Verify an authoriser's credentials and their authority for {@code permissionCode}. Returns
     * {@code authorised: false} with a friendly message rather than an error status when the check
     * fails, so the client can let the manager retype without unwinding the sale.
     */
    @PostMapping("/verify-authority")
    @PreAuthorize("isAuthenticated()")
    public AuthorityVerificationDto verifyAuthority(@Valid @RequestBody VerifyAuthorityRequest request) {
        return stepUp.verifyAuthority(
                request.username(), request.password(), request.permissionCode());
    }
}
