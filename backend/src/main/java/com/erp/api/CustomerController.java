package com.erp.api;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateCustomerRequest;
import com.erp.modules.parties.service.CustomerService;
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
 * Customer master administration (FR-PARTY-01, ADR-0006 D-10/D-11).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("@perm.has('CUSTOMER.VIEW')")
    public ApiResponse<List<CustomerDto>> list(@RequestParam Long companyId,
                                               @RequestParam(required = false) String q,
                                               Pageable pageable) {
        Page<CustomerDto> page = customers.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('CUSTOMER.VIEW')")
    public CustomerDto get(@PathVariable String uid) {
        return customers.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return customers.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'customer','CUSTOMER.MANAGE')")
    public CustomerDto update(@PathVariable String uid,
                              @Valid @RequestBody UpdateCustomerRequest request) {
        return customers.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'customer','CUSTOMER.MANAGE')")
    public void archive(@PathVariable String uid) {
        customers.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'customer','CUSTOMER.MANAGE')")
    public void restore(@PathVariable String uid) {
        customers.restoreByUid(uid);
    }

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.has('CUSTOMER.VIEW')")
    public List<PartyBranchDto> listBranches(@PathVariable String uid) {
        return customers.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'customer','PARTY.BRANCH.ASSIGN')")
    public PartyBranchDto assignBranch(@PathVariable String uid,
                                       @Valid @RequestBody AssignPartyBranchRequest request) {
        return customers.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'customer','PARTY.BRANCH.ASSIGN')")
    public void removeBranch(@PathVariable String uid, @PathVariable String branchUid) {
        customers.removeBranch(uid, branchUid);
    }
}
