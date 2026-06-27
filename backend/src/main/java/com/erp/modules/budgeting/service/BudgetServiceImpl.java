package com.erp.modules.budgeting.service;

import com.erp.modules.budgeting.domain.dto.ApproveBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.BudgetDto;
import com.erp.modules.budgeting.domain.dto.BudgetLineDto;
import com.erp.modules.budgeting.domain.dto.BudgetVersionDto;
import com.erp.modules.budgeting.domain.dto.CreateBudgetRequest;
import com.erp.modules.budgeting.domain.dto.CreateBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.RejectBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest;
import com.erp.modules.budgeting.domain.entity.Budget;
import com.erp.modules.budgeting.domain.entity.BudgetLine;
import com.erp.modules.budgeting.domain.entity.BudgetVersion;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import com.erp.modules.budgeting.repository.BudgetLineRepository;
import com.erp.modules.budgeting.repository.BudgetRepository;
import com.erp.modules.budgeting.repository.BudgetVersionRepository;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.service.DimensionService;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Budget CRUD + version lifecycle (ADR-0034 D-1/D-5/D-8).
 *
 * <p>Budgets post NOTHING to GL (BR-BUD-01, D-7) — no GLPostingService call, no GlConfigKey,
 * no JournalSourceType. assertCanActIn on every method (NFR-BUD-01).
 *
 * <p>The approve() method serialises the supersede in one TX with PESSIMISTIC_WRITE on the prior
 * approved version (D-5, NFR-BUD-04). The partial unique index is the DB backstop.
 */
@Service
public class BudgetServiceImpl implements BudgetService {

    private static final String CURRENCY_TZS = "TZS";

    private final BudgetRepository budgets;
    private final BudgetVersionRepository versions;
    private final BudgetLineRepository lines;
    private final FiscalYearRepository fiscalYears;
    private final FiscalPeriodRepository fiscalPeriods;
    private final ChartOfAccountRepository accounts;
    private final DimensionService dimensionService;
    private final BudgetingNumberGenerator numberGenerator;
    private final BudgetSpreadCalculator spreadCalculator;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public BudgetServiceImpl(BudgetRepository budgets,
                             BudgetVersionRepository versions,
                             BudgetLineRepository lines,
                             FiscalYearRepository fiscalYears,
                             FiscalPeriodRepository fiscalPeriods,
                             ChartOfAccountRepository accounts,
                             DimensionService dimensionService,
                             BudgetingNumberGenerator numberGenerator,
                             BudgetSpreadCalculator spreadCalculator,
                             ScopeGuard scopeGuard,
                             AuditService audit) {
        this.budgets          = budgets;
        this.versions         = versions;
        this.lines            = lines;
        this.fiscalYears      = fiscalYears;
        this.fiscalPeriods    = fiscalPeriods;
        this.accounts         = accounts;
        this.dimensionService = dimensionService;
        this.numberGenerator  = numberGenerator;
        this.spreadCalculator = spreadCalculator;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
    }

    // -------------------------------------------------------------------------
    // Budget CRUD
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public BudgetDto createBudget(CreateBudgetRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, request.companyId());

        FiscalYear fy = fiscalYears.findByUid(request.fiscalYearUid())
                .filter(y -> y.getCompanyId().equals(request.companyId()))
                .orElseThrow(() -> NotFoundException.of("FiscalYear", request.fiscalYearUid()));

        // Resolve cost-centre value (ADR-0025 D-3 consume; null = company-wide)
        Long ccValueId = null;
        if (request.costCentreValueUid() != null) {
            DimensionValueDto ccValue = dimensionService.resolveValue(request.costCentreValueUid())
                    .filter(v -> v.companyId().equals(request.companyId()))
                    .orElseThrow(() -> NotFoundException.of("DimensionValue", request.costCentreValueUid()));
            if (!ccValue.active()) {
                throw new ConflictException("The selected cost centre is inactive.");
            }
            ccValueId = ccValue.id();
        }

        // Guard uniqueness (service layer — DB constraint is the backstop)
        if (budgets.existsByCompanyIdAndFiscalYearIdAndCostCentreValueId(
                request.companyId(), fy.getId(), ccValueId)) {
            throw new ConflictException(
                    "A budget already exists for this fiscal year and cost centre scope");
        }

        Long actorId = principal != null ? principal.userId() : null;
        String budgetNumber = numberGenerator.nextBudget(request.companyId());
        Budget budget = new Budget(
                request.companyId(), budgetNumber, request.name(),
                fy.getId(), ccValueId, request.notes(), actorId);
        budgets.save(budget);

        // Open initial DRAFT version (FR-BUD-06 / FR-BUD-10)
        BudgetVersion v1 = new BudgetVersion(
                budget.getId(), budget.getCompanyId(), budget.getFiscalYearId(),
                budget.getCostCentreValueId(), 1, request.initialVersionLabel(), null, actorId);
        versions.save(v1);

        audit.record(AuditEvent.of(AuditActions.BUDGET_CREATE, "budgets", budget.getId(), budget.getUid())
                .detail(Map.of("budgetNumber", budgetNumber, "fiscalYearId", fy.getId())));

        return toBudgetDto(budget, List.of(toVersionDto(v1, List.of())));
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetDto getBudgetByUid(String uid) {
        Budget budget = budgets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Budget", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), budget.getCompanyId());

        List<BudgetVersion> vlist = versions.findByBudgetIdOrderByVersionNoDesc(budget.getId());
        List<BudgetVersionDto> vDtos = vlist.stream()
                .map(v -> toVersionDto(v, List.of()))
                .toList();
        return toBudgetDto(budget, vDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BudgetDto> listBudgets(Long companyId, String fiscalYearUid,
                                       String costCentreValueUid, BudgetVersionStatus versionStatus,
                                       Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        // Defect #4: versionStatus filter was accepted but silently ignored — wire it now.
        if (versionStatus != null) {
            return budgets.findByCompanyIdAndVersionStatus(companyId, versionStatus, pageable)
                    .map(b -> toBudgetDto(b, List.of()));
        }
        if (fiscalYearUid != null) {
            FiscalYear fy = fiscalYears.findByUid(fiscalYearUid)
                    .filter(y -> y.getCompanyId().equals(companyId))
                    .orElseThrow(() -> NotFoundException.of("FiscalYear", fiscalYearUid));
            return budgets.findByCompanyIdAndFiscalYearId(companyId, fy.getId(), pageable)
                    .map(b -> toBudgetDto(b, List.of()));
        }
        return budgets.findByCompanyId(companyId, pageable)
                .map(b -> toBudgetDto(b, List.of()));
    }

    // -------------------------------------------------------------------------
    // Version management
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public BudgetVersionDto createVersion(String budgetUid, CreateBudgetVersionRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        Budget budget = budgets.findByUid(budgetUid)
                .orElseThrow(() -> NotFoundException.of("Budget", budgetUid));
        scopeGuard.assertCanActIn(principal, budget.getCompanyId());

        Long actorId = principal != null ? principal.userId() : null;
        int nextNo = versions.findMaxVersionNo(budget.getId()) + 1;

        Long seededFromId = null;
        if (request.seedFromVersionUid() != null) {
            BudgetVersion seedVer = versions.findByUid(request.seedFromVersionUid())
                    .filter(v -> v.getBudgetId().equals(budget.getId()))
                    .orElseThrow(() -> NotFoundException.of("BudgetVersion", request.seedFromVersionUid()));
            seededFromId = seedVer.getId();
        }

        BudgetVersion newVer = new BudgetVersion(
                budget.getId(), budget.getCompanyId(), budget.getFiscalYearId(),
                budget.getCostCentreValueId(), nextNo, request.label(), seededFromId, actorId);
        versions.save(newVer);

        // Copy lines if seeding (FR-BUD-08c)
        List<BudgetLine> copiedLines = new ArrayList<>();
        if (seededFromId != null) {
            List<BudgetLine> sourceLines = lines.findByBudgetVersionIdOrderByFiscalPeriodId(seededFromId);
            for (BudgetLine src : sourceLines) {
                BudgetLine copy = new BudgetLine(
                        newVer.getId(), newVer.getCompanyId(),
                        src.getAccountId(), src.getFiscalPeriodId(),
                        src.getAmount(), src.getCurrency().value(), src.getLineMemo(), actorId);
                copiedLines.add(copy);
            }
            lines.saveAll(copiedLines);
        }

        audit.record(AuditEvent.of(AuditActions.BUDGET_VERSION_CREATE, "budget_versions",
                newVer.getId(), newVer.getUid())
                .detail(Map.of("budgetId", budget.getId(), "versionNo", nextNo)));

        List<BudgetLineDto> lineDtos = copiedLines.stream().map(this::toLineDto).toList();
        return toVersionDto(newVer, lineDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetVersionDto getVersionByUid(String uid) {
        BudgetVersion ver = versions.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), ver.getCompanyId());

        List<BudgetLine> vlines = lines.findByBudgetVersionIdOrderByFiscalPeriodId(ver.getId());
        return toVersionDto(ver, vlines.stream().map(this::toLineDto).toList());
    }

    // -------------------------------------------------------------------------
    // Lines management
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public BudgetVersionDto upsertLines(String versionUid, UpsertBudgetLineRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        BudgetVersion ver = versions.findByUid(versionUid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", versionUid));
        scopeGuard.assertCanActIn(principal, ver.getCompanyId());

        // Lines are editable only while DRAFT (BR-BUD-06 / D-5)
        if (ver.getStatus() != BudgetVersionStatus.DRAFT) {
            throw new ConflictException(
                    "Budget version lines are editable only in DRAFT status; current: " + ver.getStatus());
        }

        Long actorId = principal != null ? principal.userId() : null;
        List<FiscalPeriod> fyPeriods = fiscalPeriods.findByFiscalYearIdOrderByPeriodNo(ver.getFiscalYearId());

        switch (request.mode()) {
            case DIRECT -> upsertDirectLines(ver, request.lines(), fyPeriods, actorId);
            case ANNUAL_SPREAD -> upsertAnnualSpread(ver, request.accountUid(), request.annualAmount(), fyPeriods, actorId);
            case SEED -> {
                if (request.seedFromVersionUid() == null) {
                    throw new ConflictException("seedFromVersionUid required for SEED mode");
                }
                BudgetVersion seedVer = versions.findByUid(request.seedFromVersionUid())
                        .filter(v -> v.getBudgetId().equals(ver.getBudgetId()))
                        .orElseThrow(() -> NotFoundException.of("BudgetVersion", request.seedFromVersionUid()));
                reseedLines(ver, seedVer.getId(), fyPeriods, actorId);
            }
        }

        List<BudgetLine> saved = lines.findByBudgetVersionIdOrderByFiscalPeriodId(ver.getId());
        return toVersionDto(ver, saved.stream().map(this::toLineDto).toList());
    }

    private void upsertDirectLines(BudgetVersion ver, List<UpsertBudgetLineRequest.LineInputDto> inputs,
                                   List<FiscalPeriod> fyPeriods, Long actorId) {
        if (inputs == null || inputs.isEmpty()) return;
        for (UpsertBudgetLineRequest.LineInputDto input : inputs) {
            ChartOfAccount account = accounts.findByUid(input.accountUid())
                    .filter(a -> a.getCompanyId().equals(ver.getCompanyId()) && a.isActive())
                    .orElseThrow(() -> new ConflictException("Account not found or inactive."));
            FiscalPeriod period = fiscalPeriods.findByUid(input.fiscalPeriodUid())
                    .filter(p -> p.getFiscalYearId().equals(ver.getFiscalYearId()))
                    .orElseThrow(() -> new ConflictException(
                            "Fiscal period not found or does not belong to this budget's fiscal year."));
            if (input.amount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ConflictException("Budget line amount must be >= 0 (BR-BUD-09)");
            }

            Optional<BudgetLine> existing = lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(
                    ver.getId(), account.getId(), period.getId());
            if (existing.isPresent()) {
                BudgetLine line = existing.get();
                line.setAmount(input.amount().setScale(4, RoundingMode.HALF_UP));
                line.setLineMemo(input.lineMemo());
                lines.save(line); // explicit save — do not rely on flush-mode for dirty-check (Fix #1)
            } else {
                lines.save(new BudgetLine(
                        ver.getId(), ver.getCompanyId(), account.getId(), period.getId(),
                        input.amount().setScale(4, RoundingMode.HALF_UP), CURRENCY_TZS,
                        input.lineMemo(), actorId));
            }
        }
    }

    private void upsertAnnualSpread(BudgetVersion ver, String accountUid, BigDecimal annualAmount,
                                    List<FiscalPeriod> fyPeriods, Long actorId) {
        if (accountUid == null || annualAmount == null) {
            throw new ConflictException("accountUid and annualAmount required for ANNUAL_SPREAD mode");
        }
        ChartOfAccount account = accounts.findByUid(accountUid)
                .filter(a -> a.getCompanyId().equals(ver.getCompanyId()) && a.isActive())
                .orElseThrow(() -> new ConflictException("Account not found or inactive."));

        if (fyPeriods.size() != 12) {
            throw new ConflictException("Fiscal year must have 12 periods for annual spread");
        }
        List<BigDecimal> spread = spreadCalculator.spread(annualAmount, fyPeriods.size());

        for (int i = 0; i < fyPeriods.size(); i++) {
            FiscalPeriod period = fyPeriods.get(i);
            BigDecimal amt = spread.get(i);
            Optional<BudgetLine> existing = lines.findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(
                    ver.getId(), account.getId(), period.getId());
            if (existing.isPresent()) {
                BudgetLine line = existing.get();
                line.setAmount(amt);
                lines.save(line); // explicit save — do not rely on flush-mode for dirty-check (Fix #2)
            } else {
                lines.save(new BudgetLine(
                        ver.getId(), ver.getCompanyId(), account.getId(), period.getId(),
                        amt, CURRENCY_TZS, null, actorId));
            }
        }
    }

    private void reseedLines(BudgetVersion ver, Long seedVersionId,
                             List<FiscalPeriod> fyPeriods, Long actorId) {
        // Replace all lines with copies from the seed version
        lines.deleteByBudgetVersionId(ver.getId());
        List<BudgetLine> sourceLines = lines.findByBudgetVersionIdOrderByFiscalPeriodId(seedVersionId);
        for (BudgetLine src : sourceLines) {
            lines.save(new BudgetLine(
                    ver.getId(), ver.getCompanyId(),
                    src.getAccountId(), src.getFiscalPeriodId(),
                    src.getAmount(), src.getCurrency().value(), src.getLineMemo(), actorId));
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions (D-5)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public BudgetVersionDto submit(String versionUid) {
        RequestContext.Principal principal = RequestContext.get();
        BudgetVersion ver = versions.findByUid(versionUid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", versionUid));
        scopeGuard.assertCanActIn(principal, ver.getCompanyId());

        if (ver.getStatus() != BudgetVersionStatus.DRAFT) {
            throw new ConflictException("Only a DRAFT version can be submitted; current: " + ver.getStatus());
        }
        // Must have at least one line (BR-BUD-11)
        if (!lines.existsByBudgetVersionId(ver.getId())) {
            throw new ConflictException(
                    "A budget version must have at least one line before submission (BR-BUD-11)");
        }

        Long actorId = principal != null ? principal.userId() : null;
        ver.setStatus(BudgetVersionStatus.SUBMITTED);
        ver.setSubmittedAt(Instant.now());
        ver.setSubmittedBy(actorId);
        ver.setUpdatedAt(Instant.now());
        ver.setUpdatedBy(actorId);
        versions.save(ver);

        audit.record(AuditEvent.of(AuditActions.BUDGET_VERSION_SUBMIT, "budget_versions",
                ver.getId(), ver.getUid()));

        return toVersionDto(ver, List.of());
    }

    @Override
    @Transactional
    public BudgetVersionDto recall(String versionUid) {
        RequestContext.Principal principal = RequestContext.get();
        BudgetVersion ver = versions.findByUid(versionUid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", versionUid));
        scopeGuard.assertCanActIn(principal, ver.getCompanyId());

        if (ver.getStatus() != BudgetVersionStatus.SUBMITTED) {
            throw new ConflictException("Only a SUBMITTED version can be recalled; current: " + ver.getStatus());
        }

        Long actorId = principal != null ? principal.userId() : null;
        ver.setStatus(BudgetVersionStatus.DRAFT);
        ver.setSubmittedAt(null);
        ver.setSubmittedBy(null);
        ver.setUpdatedAt(Instant.now());
        ver.setUpdatedBy(actorId);
        versions.save(ver);

        audit.record(AuditEvent.of(AuditActions.BUDGET_VERSION_RECALL, "budget_versions",
                ver.getId(), ver.getUid()));

        return toVersionDto(ver, List.of());
    }

    @Override
    @Transactional
    public BudgetVersionDto approve(String versionUid, ApproveBudgetVersionRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        BudgetVersion ver = versions.findByUid(versionUid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", versionUid));
        scopeGuard.assertCanActIn(principal, ver.getCompanyId());

        if (ver.getStatus() != BudgetVersionStatus.SUBMITTED) {
            throw new ConflictException("Only a SUBMITTED version can be approved; current: " + ver.getStatus());
        }

        Long actorId = principal != null ? principal.userId() : null;

        // Supersede the prior approved version in the same TX (D-5, NFR-BUD-04).
        // Use PESSIMISTIC_WRITE to serialise concurrent approves.
        // Flush the SUPERSEDED update immediately so the status UPDATE reaches Postgres before
        // the UPDATE that sets the new version APPROVED — prevents a momentary two-row violation
        // on the partial unique index uq_budget_version_one_approved (BUDGETING-040).
        Optional<BudgetVersion> priorApproved = versions.findApprovedForUpdate(
                ver.getCompanyId(), ver.getFiscalYearId(), ver.getCostCentreValueId(),
                BudgetVersionStatus.APPROVED);
        priorApproved.ifPresent(prior -> {
            prior.setStatus(BudgetVersionStatus.SUPERSEDED);
            prior.setSupersededAt(Instant.now());
            prior.setUpdatedAt(Instant.now());
            prior.setUpdatedBy(actorId);
            versions.saveAndFlush(prior);
        });

        ver.setStatus(BudgetVersionStatus.APPROVED);
        ver.setApprovedAt(Instant.now());
        ver.setApprovedBy(actorId);
        ver.setDecisionReason(request != null ? request.note() : null);
        ver.setUpdatedAt(Instant.now());
        ver.setUpdatedBy(actorId);
        versions.save(ver);

        audit.record(AuditEvent.of(AuditActions.BUDGET_VERSION_APPROVE, "budget_versions",
                ver.getId(), ver.getUid())
                .detail(priorApproved.map(p -> Map.<String, Object>of(
                        "supersededVersionId", p.getId(), "supersededVersionUid", p.getUid()))
                        .orElse(Map.of())));

        return toVersionDto(ver, List.of());
    }

    @Override
    @Transactional
    public BudgetVersionDto reject(String versionUid, RejectBudgetVersionRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        BudgetVersion ver = versions.findByUid(versionUid)
                .orElseThrow(() -> NotFoundException.of("BudgetVersion", versionUid));
        scopeGuard.assertCanActIn(principal, ver.getCompanyId());

        if (ver.getStatus() != BudgetVersionStatus.SUBMITTED) {
            throw new ConflictException("Only a SUBMITTED version can be rejected; current: " + ver.getStatus());
        }

        Long actorId = principal != null ? principal.userId() : null;
        ver.setStatus(BudgetVersionStatus.REJECTED);
        ver.setRejectedAt(Instant.now());
        ver.setRejectedBy(actorId);
        ver.setDecisionReason(request.reason());
        ver.setUpdatedAt(Instant.now());
        ver.setUpdatedBy(actorId);
        versions.save(ver);

        audit.record(AuditEvent.of(AuditActions.BUDGET_VERSION_REJECT, "budget_versions",
                ver.getId(), ver.getUid())
                .detail(Map.of("reason", request.reason())));

        return toVersionDto(ver, List.of());
    }

    // -------------------------------------------------------------------------
    // Mappers
    // -------------------------------------------------------------------------

    private BudgetDto toBudgetDto(Budget b, List<BudgetVersionDto> vDtos) {
        // Defect #5: resolve fiscalYearUid + fiscalYearCode
        String fyUid  = null;
        String fyCode = null;
        if (b.getFiscalYearId() != null) {
            var fy = fiscalYears.findById(b.getFiscalYearId()).orElse(null);
            if (fy != null) {
                fyUid  = fy.getUid();
                fyCode = fy.getYearCode();
            }
        }
        // Defect #5: resolve costCentreValueUid + costCentreValueName via resolveValueById
        // (boundary-safe: DimensionService is the approved read contract for budgeting, D-8)
        String ccValueUid  = null;
        String ccValueName = null;
        if (b.getCostCentreValueId() != null) {
            var ccVal = dimensionService.resolveValueById(b.getCostCentreValueId()).orElse(null);
            if (ccVal != null) {
                ccValueUid  = ccVal.uid();
                ccValueName = ccVal.name();
            }
        }
        return new BudgetDto(
                b.getId(), b.getUid(), b.getCompanyId(), b.getBudgetNumber(), b.getName(),
                b.getFiscalYearId(), fyUid, fyCode,
                b.getCostCentreValueId(), ccValueUid, ccValueName,
                b.getBudgetType() != null ? b.getBudgetType().name() : null, b.getBranchId(),
                b.getNotes(), b.getVersion(),
                b.getCreatedAt(), b.getCreatedBy(), b.getUpdatedAt(), b.getUpdatedBy(),
                vDtos);
    }

    BudgetVersionDto toVersionDto(BudgetVersion v, List<BudgetLineDto> lineDtos) {
        // Defect #5: resolve budgetUid and seededFromVersionUid
        String budgetUid = budgets.findById(v.getBudgetId())
                .map(com.erp.modules.budgeting.domain.entity.Budget::getUid)
                .orElse(null);
        String seededFromVersionUid = v.getSeededFromVersionId() != null
                ? versions.findById(v.getSeededFromVersionId())
                        .map(BudgetVersion::getUid)
                        .orElse(null)
                : null;
        return new BudgetVersionDto(
                v.getId(), v.getUid(), v.getBudgetId(), budgetUid,
                v.getCompanyId(), v.getFiscalYearId(), v.getCostCentreValueId(),
                v.getVersionNo(), v.getStatus(), v.getLabel(),
                v.getSeededFromVersionId(), seededFromVersionUid,
                v.getSubmittedAt(), v.getSubmittedBy(),
                v.getApprovedAt(), v.getApprovedBy(),
                v.getRejectedAt(), v.getRejectedBy(),
                v.getSupersededAt(), v.getDecisionReason(),
                v.getVersion(), v.getCreatedAt(), v.getCreatedBy(),
                lineDtos);
    }

    BudgetLineDto toLineDto(BudgetLine l) {
        // Defect #5: resolve accountUid/accountCode/accountName and fiscalPeriodUid/periodNo
        String accountUid  = null;
        String accountCode = null;
        String accountName = null;
        if (l.getAccountId() != null) {
            var acct = accounts.findById(l.getAccountId()).orElse(null);
            if (acct != null) {
                accountUid  = acct.getUid();
                accountCode = acct.getAccountCode();
                accountName = acct.getName();
            }
        }
        String fiscalPeriodUid = null;
        int    periodNo        = 0;
        if (l.getFiscalPeriodId() != null) {
            var period = fiscalPeriods.findById(l.getFiscalPeriodId()).orElse(null);
            if (period != null) {
                fiscalPeriodUid = period.getUid();
                periodNo        = period.getPeriodNo();
            }
        }
        return new BudgetLineDto(
                l.getId(), l.getUid(), l.getBudgetVersionId(),
                l.getAccountId(), accountUid, accountCode, accountName,
                l.getFiscalPeriodId(), fiscalPeriodUid, periodNo,
                l.getAmount(), l.getQuantity(), l.getCurrency().value(), l.getLineMemo(), l.getCreatedAt());
    }
}
