package com.erp.modules.hr.service;

import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.hr.domain.dto.CreatePayComponentRequest;
import com.erp.modules.hr.domain.dto.PayComponentDto;
import com.erp.modules.hr.domain.entity.PayComponent;
import com.erp.modules.hr.repository.PayComponentRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PayComponentServiceImpl implements PayComponentService {

    private final PayComponentRepository   payComponents;
    private final ChartOfAccountRepository glAccounts;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public PayComponentServiceImpl(PayComponentRepository payComponents,
                                    ChartOfAccountRepository glAccounts,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.payComponents = payComponents;
        this.glAccounts    = glAccounts;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    @Override
    public PayComponentDto create(CreatePayComponentRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);
        if (payComponents.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException("Pay component code already exists: " + req.code());
        }
        // #23 — resolve GL account before persistence; unknown id returns 404 not 500
        if (!glAccounts.existsById(req.glAccountId())) {
            throw NotFoundException.of("GlAccount", String.valueOf(req.glAccountId()));
        }
        PayComponent pc = payComponents.save(new PayComponent(companyId, req.code(), req.name(),
                req.kind(), req.basis(), req.glAccountId(),
                req.taxable(), req.pensionable(), p.userId()));
        audit.record(AuditEvent.of(AuditActions.HR_PAYCOMPONENT_CREATE, "pay_components", pc.getId(), null));
        return toDto(pc);
    }

    @Override
    @Transactional(readOnly = true)
    public PayComponentDto getByUid(String uid) {
        PayComponent pc = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pc.getCompanyId());
        return toDto(pc);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayComponentDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payComponents.findByCompanyIdAndActiveTrue(companyId).stream().map(this::toDto).toList();
    }

    @Override
    public PayComponentDto update(String uid, CreatePayComponentRequest req) {
        PayComponent pc = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pc.getCompanyId());
        RequestContext.Principal p = RequestContext.get();
        if (!pc.getCode().equals(req.code())
                && payComponents.existsByCompanyIdAndCode(pc.getCompanyId(), req.code())) {
            throw new ConflictException("Pay component code already exists: " + req.code());
        }
        // #23 — resolve GL account before persistence
        if (!glAccounts.existsById(req.glAccountId())) {
            throw NotFoundException.of("GlAccount", String.valueOf(req.glAccountId()));
        }
        pc.setCode(req.code());
        pc.setName(req.name());
        pc.setBasis(req.basis());
        pc.setGlAccountId(req.glAccountId());
        pc.setTaxable(req.taxable());
        pc.setPensionable(req.pensionable());
        pc.setUpdatedAt(Instant.now());
        pc.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_PAYCOMPONENT_UPDATE, "pay_components", pc.getId(), null));
        return toDto(pc);
    }

    @Override
    public void deactivate(String uid) {
        PayComponent pc = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pc.getCompanyId());
        pc.setActive(false);
        pc.setUpdatedAt(Instant.now());
        pc.setUpdatedBy(RequestContext.get().userId());
    }

    private PayComponent requireByUid(String uid) {
        return payComponents.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("PayComponent", uid));
    }

    private PayComponentDto toDto(PayComponent pc) {
        return new PayComponentDto(pc.getId(), pc.getUid(), pc.getCompanyId(),
                pc.getCode(), pc.getName(), pc.getKind(), pc.getBasis(),
                pc.getGlAccountId(), pc.isTaxable(), pc.isPensionable(), pc.isActive(),
                pc.isWcfApplicable(), pc.isSdlApplicable(), pc.getDisplayOrder(), pc.isProRatable());
    }
}
