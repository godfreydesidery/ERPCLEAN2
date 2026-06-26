package com.erp.api;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * User administration by uid (ARCHITECTURE §7). Returns raw DTOs — {@code ApiResponseAdvice} wraps
 * them. The permission gate is {@code @perm.has('USER.*')} (not the company-scoped variant); the
 * company-membership tenant-isolation check is enforced inside {@code UserServiceImpl} via
 * {@code requireInScope} on every mutating operation (security audit 2026-06-26). Read-only
 * list/get paths apply the same membership check. {@code is_root} is never settable here.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("@perm.has('USER.VIEW')")
    public List<UserDto> list() {
        return users.list();
    }

    /**
     * Org-wide user picker — returns ALL users in the organisation regardless of company membership.
     * Intended for the assign-role flow where the caller legitimately needs to find a user not yet
     * in their company. Gated on USER.MANAGE (a stronger gate than VIEW) so read-only auditors
     * cannot enumerate the whole org.
     */
    @GetMapping("/org-wide")
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public List<UserDto> listOrgWide() {
        return users.listOrgWide();
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('USER.VIEW')")
    public UserDto get(@PathVariable String uid) {
        return users.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public UserDto update(@PathVariable String uid, @Valid @RequestBody UpdateUserRequest request) {
        return users.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public void disable(@PathVariable String uid) {
        users.disableByUid(uid);
    }

    @PutMapping("/uid/{uid}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public void enable(@PathVariable String uid) {
        users.enableByUid(uid);
    }

    @PutMapping("/uid/{uid}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public void unlock(@PathVariable String uid) {
        users.unlockByUid(uid);
    }

    @PutMapping("/uid/{uid}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('USER.MANAGE')")
    public void setPassword(@PathVariable String uid, @Valid @RequestBody SetPasswordRequest request) {
        users.setPasswordByUid(uid, request);
    }
}
