package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PosTillServiceImpl implements PosTillService {

    private final PosTillRepository  tills;
    private final CompanyRepository  companies;
    private final ScopeGuard         scopeGuard;
    private final AuditService       audit;

    public PosTillServiceImpl(PosTillRepository tills,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.tills     = tills;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit     = audit;
    }

    @Override
    public PosTillDto createTill(CreatePosTillRequest req) {
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        var till = new PosTill(company.getId(), req.branchId(), req.name(), actorId());
        till = tills.save(till);

        audit.record(AuditEvent.of(AuditActions.POS_TILL_CREATE, "pos_tills", till.getId(), till.getUid())
                .detail(Map.of("name", till.getName())));
        return toDto(till);
    }

    @Override
    @Transactional(readOnly = true)
    public PosTillDto getTillByUid(String uid) {
        var till = requireTill(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), till.getCompanyId());
        return toDto(till);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PosTillDto> listTillsByBranch(Long companyId, Long branchId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return tills.findByCompanyIdAndBranchId(companyId, branchId)
                .stream().map(this::toDto).toList();
    }

    @Override
    public void deactivateTill(String uid) {
        var till = requireTill(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), till.getCompanyId());
        till.setStatus(MasterStatus.INACTIVE);
        till.setUpdatedAt(Instant.now());
        till.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.POS_TILL_DEACTIVATE, "pos_tills", till.getId(), till.getUid()));
    }

    private PosTill requireTill(String uid) {
        return tills.findByUid(uid).orElseThrow(() -> NotFoundException.of("PosTill", uid));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    private PosTillDto toDto(PosTill t) {
        return new PosTillDto(t.getId(), t.getUid(), t.getCompanyId(), t.getBranchId(),
                t.getName(), t.getStatus());
    }
}
