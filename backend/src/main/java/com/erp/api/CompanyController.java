package com.erp.api;

import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
import com.erp.modules.iam.service.CompanyService;
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
 * Company CRUD by uid (ARCHITECTURE §7). Returns raw DTOs — {@code ApiResponseAdvice} wraps them in
 * the envelope. Permission-gated (ADR-0002): list/create use {@code @perm.has} (no target uid),
 * target ops add the same-company scope check via {@code @perm.scoped(#uid,'company',CODE)}.
 */
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @GetMapping
    @PreAuthorize("@perm.has('COMPANY.VIEW')")
    public List<CompanyDto> list(@RequestParam String organisationUid) {
        return companies.listByOrganisationUid(organisationUid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'company', 'COMPANY.VIEW')")
    public CompanyDto get(@PathVariable String uid) {
        return companies.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('COMPANY.MANAGE')")
    public CompanyDto create(@Valid @RequestBody CreateCompanyRequest request) {
        return companies.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'company', 'COMPANY.MANAGE')")
    public CompanyDto update(@PathVariable String uid,
                             @Valid @RequestBody UpdateCompanyRequest request) {
        return companies.updateByUid(uid, request);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'company', 'COMPANY.MANAGE')")
    public void archive(@PathVariable String uid) {
        companies.archiveByUid(uid);
    }
}
