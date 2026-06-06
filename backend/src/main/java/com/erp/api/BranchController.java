package com.erp.api;

import com.erp.modules.iam.domain.dto.BranchDto;
import com.erp.modules.iam.domain.dto.CreateBranchRequest;
import com.erp.modules.iam.domain.dto.UpdateBranchRequest;
import com.erp.modules.iam.service.BranchService;
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
 * Branch CRUD by uid, plus the set-default action (ARCHITECTURE §7). Permission gate
 * (BRANCH.MANAGE) wired in Slice 3.
 */
@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private final BranchService branches;

    public BranchController(BranchService branches) {
        this.branches = branches;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#companyUid, 'company', 'BRANCH.VIEW')")
    public List<BranchDto> list(@RequestParam String companyUid) {
        return branches.listByCompanyUid(companyUid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("hasPermission(#uid, 'branch', 'BRANCH.VIEW')")
    public BranchDto get(@PathVariable String uid) {
        return branches.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#request.companyUid(), 'company', 'BRANCH.MANAGE')")
    public BranchDto create(@Valid @RequestBody CreateBranchRequest request) {
        return branches.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("hasPermission(#uid, 'branch', 'BRANCH.MANAGE')")
    public BranchDto update(@PathVariable String uid,
                            @Valid @RequestBody UpdateBranchRequest request) {
        return branches.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/default")
    @PreAuthorize("hasPermission(#uid, 'branch', 'BRANCH.MANAGE')")
    public BranchDto setDefault(@PathVariable String uid) {
        return branches.setDefault(uid);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#uid, 'branch', 'BRANCH.MANAGE')")
    public void archive(@PathVariable String uid) {
        branches.archiveByUid(uid);
    }
}
