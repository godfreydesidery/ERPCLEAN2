package com.erp.api;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.service.UserRoleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-assignment lifecycle: grant, revoke, list (DATA-MODEL §1.8, ADR-0002 D-3). Uses
 * {@code @perm.has} — scope enforcement (the grant's company == active company) is delegated to
 * {@code UserRoleServiceImpl} via {@code ScopeGuard.assertCanActIn}. Returns raw DTOs —
 * {@code ApiResponseAdvice} wraps them in the envelope. Uses {@code @perm.has} (ADR-0002 Bug-1 fix).
 */
@RestController
@RequestMapping("/api/v1/user-roles")
public class UserRoleController {

    private final UserRoleService userRoleService;

    public UserRoleController(UserRoleService userRoleService) {
        this.userRoleService = userRoleService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('ROLE.MANAGE')")
    public UserRoleDto grant(@Valid @RequestBody GrantRoleRequest request) {
        return userRoleService.grant(request);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('ROLE.MANAGE')")
    public void revoke(@PathVariable String uid) {
        userRoleService.revoke(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('ROLE.VIEW')")
    public List<UserRoleDto> listForUser(@RequestParam String userUid) {
        return userRoleService.listForUser(userUid);
    }
}
