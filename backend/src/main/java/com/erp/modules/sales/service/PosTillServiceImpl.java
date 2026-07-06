package com.erp.modules.sales.service;

import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
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

    private final PosTillRepository          tills;
    private final CompanyRepository          companies;
    private final CashBankAccountRepository  cashAccounts;
    private final PosSessionRepository       sessions;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final SalesDepthNumberGenerator  numberGen;

    public PosTillServiceImpl(PosTillRepository tills,
                               CompanyRepository companies,
                               CashBankAccountRepository cashAccounts,
                               PosSessionRepository sessions,
                               ScopeGuard scopeGuard,
                               AuditService audit,
                               SalesDepthNumberGenerator numberGen) {
        this.tills        = tills;
        this.companies    = companies;
        this.cashAccounts = cashAccounts;
        this.sessions     = sessions;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
        this.numberGen    = numberGen;
    }

    @Override
    public PosTillDto createTill(CreatePosTillRequest req) {
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        Long cashAccountId = resolveCashAccount(company.getId(), req.cashBankAccountUid());

        var till = new PosTill(company.getId(), req.branchId(), req.name(),
                cashAccountId, actorId());
        // Generate a short per-company code (was left null forever — busy-day-sim bugfix; the
        // open-shift card needs something more legible than a blank field).
        till.setCode(numberGen.nextPosTill(company.getId()));
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

    /**
     * Resolves the cash/bank account id for a new till.
     * If {@code uid} is provided, fetch by (companyId, uid).
     * Otherwise fall back to the company's default active cash account.
     * Throws {@link ConflictException} if no usable account exists.
     */
    private Long resolveCashAccount(Long companyId, String uid) {
        if (uid != null && !uid.isBlank()) {
            return cashAccounts.findByCompanyIdAndUid(companyId, uid)
                    .orElseThrow(() -> NotFoundException.of("CashBankAccount", uid))
                    .getId();
        }
        // Default: company's default cash account
        return cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId)
                .or(() -> cashAccounts.findByCompanyIdAndActive(companyId, true)
                        .stream().findFirst())
                .orElseThrow(() -> new ConflictException(
                        "No cash/bank account is configured for this company. Please create a cash account before setting up a POS till."))
                .getId();
    }

    private PosTillDto toDto(PosTill t) {
        boolean hasOpenSession = sessions
                .findByPosTillIdAndStatus(t.getId(), PosSessionStatus.OPEN)
                .isPresent();
        return new PosTillDto(t.getId(), t.getUid(), t.getCompanyId(), t.getBranchId(),
                t.getCode(), t.getName(), t.getCashBankAccountId(), t.getStatus(), hasOpenSession);
    }
}
