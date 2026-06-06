package com.erp.api;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * Supplier master administration (FR-PARTY-02, ADR-0006 D-10/D-11).
 */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public ApiResponse<List<SupplierDto>> list(@RequestParam Long companyId,
                                               @RequestParam(required = false) String q,
                                               Pageable pageable) {
        Page<SupplierDto> page = suppliers.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public SupplierDto get(@PathVariable String uid) {
        return suppliers.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public SupplierDto create(@Valid @RequestBody CreateSupplierRequest request) {
        return suppliers.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'supplier','SUPPLIER.MANAGE')")
    public SupplierDto update(@PathVariable String uid,
                              @Valid @RequestBody UpdateSupplierRequest request) {
        return suppliers.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'supplier','SUPPLIER.MANAGE')")
    public void archive(@PathVariable String uid) {
        suppliers.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'supplier','SUPPLIER.MANAGE')")
    public void restore(@PathVariable String uid) {
        suppliers.restoreByUid(uid);
    }

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public List<PartyBranchDto> listBranches(@PathVariable String uid) {
        return suppliers.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'supplier','PARTY.BRANCH.ASSIGN')")
    public PartyBranchDto assignBranch(@PathVariable String uid,
                                       @Valid @RequestBody AssignPartyBranchRequest request) {
        return suppliers.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'supplier','PARTY.BRANCH.ASSIGN')")
    public void removeBranch(@PathVariable String uid, @PathVariable String branchUid) {
        suppliers.removeBranch(uid, branchUid);
    }
}
