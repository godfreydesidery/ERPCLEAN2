package com.erp.api;

import com.erp.modules.crm.domain.dto.AddOpportunityLineRequest;
import com.erp.modules.crm.domain.dto.AdvanceStageRequest;
import com.erp.modules.crm.domain.dto.ConvertOpportunityRequest;
import com.erp.modules.crm.domain.dto.ConvertResultDto;
import com.erp.modules.crm.domain.dto.CreateOpportunityRequest;
import com.erp.modules.crm.domain.dto.LoseOpportunityRequest;
import com.erp.modules.crm.domain.dto.OpportunityDto;
import com.erp.modules.crm.domain.dto.OpportunityLineDto;
import com.erp.modules.crm.domain.dto.UpdateOpportunityRequest;
import com.erp.modules.crm.domain.dto.WinOpportunityRequest;
import com.erp.modules.crm.service.OpportunityConversionService;
import com.erp.modules.crm.service.OpportunityService;
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
 * CRM Opportunity REST controller (ADR-0031 D-11).
 * Permission codes seeded in V51__crm.sql.
 */
@RestController
@RequestMapping("/api/v1/crm/opportunities")
public class OpportunityController {

    private final OpportunityService opportunityService;
    private final OpportunityConversionService conversionService;

    public OpportunityController(OpportunityService opportunityService,
                                 OpportunityConversionService conversionService) {
        this.opportunityService = opportunityService;
        this.conversionService = conversionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CRM.OPPORTUNITY.MANAGE')")
    public OpportunityDto create(@Valid @RequestBody CreateOpportunityRequest request) {
        return opportunityService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.VIEW')")
    public OpportunityDto getByUid(@PathVariable String uid) {
        return opportunityService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CRM.OPPORTUNITY.VIEW')")
    public ApiResponse<List<OpportunityDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<OpportunityDto> page = opportunityService.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public OpportunityDto update(@PathVariable String uid, @Valid @RequestBody UpdateOpportunityRequest request) {
        return opportunityService.updateByUid(uid, request);
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.VIEW')")
    public List<OpportunityLineDto> listLines(@PathVariable String uid) {
        return opportunityService.listLines(uid);
    }

    @PostMapping("/uid/{uid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public OpportunityLineDto addLine(@PathVariable String uid,
                                      @Valid @RequestBody AddOpportunityLineRequest request) {
        return opportunityService.addLine(uid, request);
    }

    @DeleteMapping("/uid/{uid}/lines/{lineUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public void removeLine(@PathVariable String uid, @PathVariable String lineUid) {
        opportunityService.removeLine(uid, lineUid);
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/advance-stage")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public OpportunityDto advanceStage(@PathVariable String uid,
                                       @Valid @RequestBody AdvanceStageRequest request) {
        return opportunityService.advanceStage(uid, request);
    }

    @PostMapping("/uid/{uid}/win")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public OpportunityDto win(@PathVariable String uid, @Valid @RequestBody WinOpportunityRequest request) {
        return opportunityService.win(uid, request);
    }

    @PostMapping("/uid/{uid}/lose")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.MANAGE')")
    public OpportunityDto lose(@PathVariable String uid, @Valid @RequestBody LoseOpportunityRequest request) {
        return opportunityService.lose(uid, request);
    }

    @PostMapping("/uid/{uid}/convert")
    @PreAuthorize("@perm.scoped(#uid,'opportunity','CRM.OPPORTUNITY.CONVERT')")
    public ConvertResultDto convert(@PathVariable String uid,
                                    @Valid @RequestBody ConvertOpportunityRequest request) {
        return conversionService.convert(uid, request);
    }
}
