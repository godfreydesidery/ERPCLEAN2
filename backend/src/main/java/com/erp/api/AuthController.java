package com.erp.api;

import com.erp.modules.iam.domain.dto.LoginRequest;
import com.erp.modules.iam.domain.dto.MeResponse;
import com.erp.modules.iam.domain.dto.RefreshRequest;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (ARCHITECTURE §4). login/refresh/logout are public (permitted in
 * SecurityConfig); {@code /me} requires a valid token. Responses are wrapped in the ApiResponse
 * envelope.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        return auth.login(request.username(), request.password(), httpRequest.getRemoteAddr());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
    }

    /** The current caller + their effective permissions for the active scope (drives the UI). */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public MeResponse me() {
        return auth.me();
    }

    /** The caller's own switchable branches (ADR-0003 D-6) — self-scoped, no USER.VIEW required. */
    @GetMapping("/my-branches")
    @PreAuthorize("isAuthenticated()")
    public List<UserBranchDto> myBranches() {
        return auth.myBranches();
    }
}
