package com.erp.api;

import com.erp.modules.crm.domain.dto.CreatePipelineStageRequest;
import com.erp.modules.crm.domain.dto.PipelineStageDto;
import com.erp.modules.crm.domain.dto.UpdatePipelineStageRequest;
import com.erp.modules.crm.service.PipelineStageService;
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
 * CRM Pipeline Stage REST controller (ADR-0031 D-11).
 * Permission codes seeded in V51__crm.sql.
 */
@RestController
@RequestMapping("/api/v1/crm/pipeline-stages")
public class PipelineStageController {

    private final PipelineStageService stageService;

    public PipelineStageController(PipelineStageService stageService) {
        this.stageService = stageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CRM.STAGE.MANAGE')")
    public PipelineStageDto create(@Valid @RequestBody CreatePipelineStageRequest request) {
        return stageService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pipelinestage','CRM.STAGE.VIEW')")
    public PipelineStageDto getByUid(@PathVariable String uid) {
        return stageService.getByUid(uid);
    }

    /** All stages for a company, ordered by displayOrder. */
    @GetMapping
    @PreAuthorize("@perm.has('CRM.STAGE.VIEW')")
    public List<PipelineStageDto> listByCompany(@RequestParam Long companyId) {
        return stageService.listByCompany(companyId);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pipelinestage','CRM.STAGE.MANAGE')")
    public PipelineStageDto update(@PathVariable String uid,
                                    @Valid @RequestBody UpdatePipelineStageRequest request) {
        return stageService.updateByUid(uid, request);
    }

    /** Soft-deactivate: sets isActive=false, does not delete. */
    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'pipelinestage','CRM.STAGE.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        stageService.deactivateByUid(uid);
    }
}
