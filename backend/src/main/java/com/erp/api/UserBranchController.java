package com.erp.api;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.service.UserBranchService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Branch assignment (US-IAM-006). Gated by {@code BRANCH.ASSIGN}. {@code assign} scopes on the
 * target branch's company declaratively ({@code @perm.scoped} on the body's branchUid). set-default
 * and remove address an assignment by its uid (not a branch), so their company scope is enforced in
 * the service via {@code ScopeGuard.assertCanActIn} (body-scoped pattern, ADR-0002) — the gate here
 * only checks the permission is held.
 */
@RestController
@RequestMapping("/api/v1/user-branches")
public class UserBranchController {

    private final UserBranchService userBranches;

    public UserBranchController(UserBranchService userBranches) {
        this.userBranches = userBranches;
    }

    @GetMapping
    @PreAuthorize("@perm.has('USER.VIEW')")
    public List<UserBranchDto> listForUser(@RequestParam String userUid) {
        return userBranches.listForUser(userUid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.branchUid(), 'branch', 'BRANCH.ASSIGN')")
    public UserBranchDto assign(@Valid @RequestBody AssignBranchRequest request) {
        return userBranches.assign(request);
    }

    @PutMapping("/uid/{uid}/default")
    @PreAuthorize("@perm.has('BRANCH.ASSIGN')")
    public UserBranchDto setDefault(@PathVariable String uid) {
        return userBranches.setDefault(uid);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('BRANCH.ASSIGN')")
    public void remove(@PathVariable String uid) {
        userBranches.remove(uid);
    }
}
