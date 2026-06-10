package com.erp.modules.tax.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.UpdateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.domain.entity.WhtType;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WHT type CRUD (ADR-0017 D-2d, FR-WHT-01/02/03).
 */
@Service
@Transactional
public class WhtTypeServiceImpl implements WhtTypeService {

    private final WhtTypeRepository whtTypes;
    private final CompanyRepository companies;
    private final ScopeGuard        scopeGuard;
    private final AuditService      audit;

    public WhtTypeServiceImpl(WhtTypeRepository whtTypes,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.whtTypes  = whtTypes;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public WhtTypeDto create(CreateWhtTypeRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        if (whtTypes.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException(
                    "WHT type code '" + req.code() + "' already exists for this company.");
        }

        WhtType whtType = new WhtType(companyId, req.code(), req.name(),
                req.kind(), req.ratePct(), actorId());
        whtType = whtTypes.save(whtType);

        audit.record(AuditEvent.of(AuditActions.WHT_TYPE_MANAGE, "wht_types",
                whtType.getId(), whtType.getUid())
                .detail(Map.of("action", "create", "code", req.code(), "kind", req.kind().name())));

        return toDto(whtType);
    }

    @Override
    public WhtTypeDto update(String uid, UpdateWhtTypeRequest req) {
        WhtType whtType = Lookups.orNotFound(whtTypes.findByUid(uid), "WhtType", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), whtType.getCompanyId());

        whtType.setName(req.name());
        whtType.setRatePct(req.ratePct());
        whtType.setActive(req.active());
        whtType.setUpdatedAt(Instant.now());
        whtType.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.WHT_TYPE_MANAGE, "wht_types",
                whtType.getId(), uid)
                .detail(Map.of("action", "update")));

        return toDto(whtType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WhtTypeDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return whtTypes.findByCompanyId(companyId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WhtTypeDto getByUid(String uid) {
        WhtType whtType = Lookups.orNotFound(whtTypes.findByUid(uid), "WhtType", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), whtType.getCompanyId());
        return toDto(whtType);
    }

    @Override
    public WhtTypeDto deactivate(String uid) {
        WhtType whtType = Lookups.orNotFound(whtTypes.findByUid(uid), "WhtType", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), whtType.getCompanyId());

        whtType.setActive(false);
        whtType.setUpdatedAt(Instant.now());
        whtType.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.WHT_TYPE_MANAGE, "wht_types",
                whtType.getId(), uid)
                .detail(Map.of("action", "deactivate")));

        return toDto(whtType);
    }

    // -------------------------------------------------------------------------

    private WhtTypeDto toDto(WhtType t) {
        return new WhtTypeDto(
                t.getId(), t.getUid(), t.getCompanyId(),
                t.getCode(), t.getName(), t.getKind(),
                t.getRatePct(), t.isActive());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
