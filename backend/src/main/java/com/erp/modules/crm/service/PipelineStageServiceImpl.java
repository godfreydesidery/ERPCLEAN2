package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.CreatePipelineStageRequest;
import com.erp.modules.crm.domain.dto.PipelineStageDto;
import com.erp.modules.crm.domain.dto.UpdatePipelineStageRequest;
import com.erp.modules.crm.domain.entity.PipelineStage;
import com.erp.modules.crm.repository.PipelineStageRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PipelineStageServiceImpl implements PipelineStageService {

    private final PipelineStageRepository stages;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public PipelineStageServiceImpl(PipelineStageRepository stages,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.stages = stages;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public PipelineStageDto create(CreatePipelineStageRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        if (stages.existsByCompanyIdAndName(req.companyId(), req.name())) {
            throw new IllegalArgumentException("Pipeline stage name already exists: " + req.name());
        }
        PipelineStage stage = new PipelineStage(req.companyId(), req.name(),
                req.displayOrder().shortValue(), req.defaultProbability(), actorId());
        PipelineStage saved = stages.save(stage);
        audit.record(AuditEvent.of(AuditActions.CRM_STAGE_CREATE, "pipeline_stages",
                saved.getId(), saved.getUid())
                .detail(Map.of("name", req.name(), "companyId", String.valueOf(req.companyId()))));
        return PipelineStageDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PipelineStageDto getByUid(String uid) {
        PipelineStage stage = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), stage.getCompanyId());
        return PipelineStageDto.from(stage);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PipelineStageDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return stages.findByCompanyIdOrderByDisplayOrder(companyId)
                .stream().map(PipelineStageDto::from).toList();
    }

    @Override
    public PipelineStageDto updateByUid(String uid, UpdatePipelineStageRequest req) {
        PipelineStage stage = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), stage.getCompanyId());
        stage.setName(req.name());
        stage.setDisplayOrder(req.displayOrder().shortValue());
        stage.setDefaultProbability(req.defaultProbability());
        stage.setActive(req.active());
        stage.setUpdatedAt(Instant.now());
        stage.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CRM_STAGE_UPDATE, "pipeline_stages",
                stage.getId(), stage.getUid()).detail(Map.of("name", req.name())));
        return PipelineStageDto.from(stage);
    }

    @Override
    public void deactivateByUid(String uid) {
        PipelineStage stage = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), stage.getCompanyId());
        stage.setActive(false);
        stage.setUpdatedAt(Instant.now());
        stage.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CRM_STAGE_DEACTIVATE, "pipeline_stages",
                stage.getId(), stage.getUid()).detail(Map.of()));
    }

    // -------------------------------------------------------------------------

    private PipelineStage require(String uid) {
        return Lookups.orNotFound(stages.findByUid(uid), "PipelineStage", uid);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
