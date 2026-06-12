package com.erp.api;

import com.erp.modules.crm.domain.dto.CreateLeadRequest;
import com.erp.modules.crm.domain.dto.DisqualifyLeadRequest;
import com.erp.modules.crm.domain.dto.LeadDto;
import com.erp.modules.crm.domain.dto.QualifyLeadRequest;
import com.erp.modules.crm.domain.dto.UpdateLeadRequest;
import com.erp.modules.crm.service.LeadService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * CRM Lead REST controller (ADR-0031 D-11).
 * Permission codes seeded in V51__crm.sql.
 */
@RestController
@RequestMapping("/api/v1/crm/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CRM.LEAD.MANAGE')")
    public LeadDto create(@Valid @RequestBody CreateLeadRequest request) {
        return leadService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'lead','CRM.LEAD.VIEW')")
    public LeadDto getByUid(@PathVariable String uid) {
        return leadService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CRM.LEAD.VIEW')")
    public ApiResponse<List<LeadDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<LeadDto> page = leadService.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'lead','CRM.LEAD.MANAGE')")
    public LeadDto update(@PathVariable String uid, @Valid @RequestBody UpdateLeadRequest request) {
        return leadService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/contact")
    @PreAuthorize("@perm.scoped(#uid,'lead','CRM.LEAD.MANAGE')")
    public LeadDto contact(@PathVariable String uid) {
        return leadService.contact(uid);
    }

    @PostMapping("/uid/{uid}/qualify")
    @PreAuthorize("@perm.scoped(#uid,'lead','CRM.LEAD.QUALIFY')")
    public LeadDto qualify(@PathVariable String uid, @RequestBody QualifyLeadRequest request) {
        return leadService.qualify(uid, request);
    }

    @PostMapping("/uid/{uid}/disqualify")
    @PreAuthorize("@perm.scoped(#uid,'lead','CRM.LEAD.MANAGE')")
    public LeadDto disqualify(@PathVariable String uid, @Valid @RequestBody DisqualifyLeadRequest request) {
        return leadService.disqualify(uid, request);
    }
}
