package com.erp.modules.fx.service;

import com.erp.modules.fx.domain.dto.BranchCurrencyDto;
import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.domain.dto.EnableCurrencyRequest;
import com.erp.modules.fx.domain.entity.BranchCurrency;
import com.erp.modules.fx.domain.entity.CompanyCurrency;
import com.erp.modules.fx.domain.exception.CurrencyNotEnabledException;
import com.erp.modules.fx.repository.BranchCurrencyRepository;
import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the currency enablement invariants defined in ADR-0039 D-7/D-8.
 *
 * <p>Placement: lives in {@code com.erp.modules.fx.service} alongside the other FX services.
 * Depends on {@code fx} and {@code iam} repositories — the same cross-module access that
 * {@code FxRateServiceImpl} already uses (ADR-0036 D-1 platform-service placement note).
 */
@Service
@Transactional
public class CurrencyEnablementServiceImpl implements CurrencyEnablementService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyEnablementServiceImpl.class);

    private static final String MSG_COMPANY_NOT_FOUND  = "The requested company was not found.";
    private static final String MSG_BRANCH_NOT_FOUND   = "The requested branch was not found.";
    private static final String CURRENCY_PREFIX        = "Currency '";

    private final CompanyCurrencyRepository companyCurrencies;
    private final BranchCurrencyRepository  branchCurrencies;
    private final CurrencyRepository        currencies;
    private final CompanyRepository         companies;
    private final BranchRepository          branches;

    public CurrencyEnablementServiceImpl(CompanyCurrencyRepository companyCurrencies,
                                         BranchCurrencyRepository  branchCurrencies,
                                         CurrencyRepository        currencies,
                                         CompanyRepository         companies,
                                         BranchRepository          branches) {
        this.companyCurrencies = companyCurrencies;
        this.branchCurrencies  = branchCurrencies;
        this.currencies        = currencies;
        this.companies         = companies;
        this.branches          = branches;
    }

    // ── UID resolution ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(Company::getId)
                .orElseThrow(() -> {
                    log.debug("resolveCompanyId: company uid {} not found", companyUid);
                    return new NotFoundException(MSG_COMPANY_NOT_FOUND);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Long resolveBranchId(String branchUid, Long companyId) {
        return branches.findByUid(branchUid)
                .filter(b -> b.getCompany().getId().equals(companyId))
                .map(b -> b.getId())
                .orElseThrow(() -> {
                    log.debug("resolveBranchId: branch uid {} not found or does not belong to company {}",
                            branchUid, companyId);
                    return new NotFoundException(MSG_BRANCH_NOT_FOUND);
                });
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CompanyCurrencyDto> enabledForCompany(Long companyId) {
        return companyCurrencies
                .findByCompanyIdAndActiveTrueOrderByCurrencyCode(companyId)
                .stream()
                .map(CompanyCurrencyDto::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchCurrencyDto> enabledForBranch(Long companyId, Long branchId) {
        List<BranchCurrency> branchList =
                branchCurrencies.findByBranchIdAndActiveTrueOrderByCurrencyCode(branchId);
        if (!branchList.isEmpty()) {
            return branchList.stream().map(BranchCurrencyDto::from).toList();
        }
        // Branch has no own list → return company list cast to BranchCurrencyDto shape
        // (branchId = null signals inheritance; callers understand this shape)
        return companyCurrencies
                .findByCompanyIdAndActiveTrueOrderByCurrencyCode(companyId)
                .stream()
                .map(cc -> new BranchCurrencyDto(
                        cc.getId(), cc.getUid(), cc.getCompanyId(), branchId,
                        cc.getCurrencyCode().value(), cc.isDefault(), cc.isActive()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyCode resolveDefault(Long companyId, Long branchId) {
        // 1) Branch default
        if (branchId != null) {
            var branchDefault = branchCurrencies.findByBranchIdAndIsDefaultTrue(branchId);
            if (branchDefault.isPresent()) {
                return branchDefault.get().getCurrencyCode();
            }
        }
        // 2) Company default
        var companyDefault = companyCurrencies.findByCompanyIdAndIsDefaultTrue(companyId);
        if (companyDefault.isPresent()) {
            return companyDefault.get().getCurrencyCode();
        }
        // 3) Company base (ultimate fallback)
        String base = companies.findById(companyId)
                .map(Company::getBaseCurrency)
                .orElseThrow(() -> {
                    log.debug("resolveDefault: company id {} not found", companyId);
                    return new NotFoundException(MSG_COMPANY_NOT_FOUND);
                });
        return CurrencyCode.of(base);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertEnabled(Long companyId, Long branchId, CurrencyCode currencyCode) {
        // If branch has its own list, check branch; else check company.
        if (branchId != null && branchCurrencies.countByBranchId(branchId) > 0) {
            boolean enabledForBranch = branchCurrencies
                    .findByBranchIdAndCurrencyCodeAndActiveTrue(branchId, currencyCode)
                    .isPresent();
            if (!enabledForBranch) {
                throw new CurrencyNotEnabledException(currencyCode, companyId, branchId);
            }
            return;
        }
        // Check company list
        boolean enabledForCompany = companyCurrencies
                .findByCompanyIdAndCurrencyCodeAndActiveTrue(companyId, currencyCode)
                .isPresent();
        if (!enabledForCompany) {
            throw new CurrencyNotEnabledException(currencyCode, companyId, null);
        }
    }

    // ── Company-level management ──────────────────────────────────────────────

    @Override
    public CompanyCurrencyDto enableForCompany(Long companyId, EnableCurrencyRequest request) {
        CurrencyCode code = CurrencyCode.of(request.currencyCode());

        // Invariant: must be globally active
        assertGloballyActive(code);

        Long actorId = actorId();

        // Idempotent: if row already exists, re-activate it
        var existing = companyCurrencies.findByCompanyIdAndCurrencyCode(companyId, code);
        CompanyCurrency cc;
        if (existing.isPresent()) {
            cc = existing.get();
            cc.setActive(true);
            cc.setUpdatedAt(Instant.now());
            cc.setUpdatedBy(actorId);
        } else {
            cc = new CompanyCurrency(companyId, code, false, true, actorId);
            cc = companyCurrencies.save(cc);
        }

        if (request.setAsDefault()) {
            shiftCompanyDefault(companyId, cc, actorId);
        }
        return CompanyCurrencyDto.from(cc);
    }

    @Override
    public CompanyCurrencyDto deactivateForCompany(Long companyId, String currencyCodeRaw) {
        CurrencyCode code = CurrencyCode.of(currencyCodeRaw);
        CompanyCurrency cc = requireCompanyCurrency(companyId, code);

        // Invariant: base currency cannot be deactivated
        String base = requireBaseCurrency(companyId);
        if (CurrencyCode.of(base).equals(code)) {
            throw new ConflictException(
                    "Cannot deactivate the company base currency '" + code.value() + "'.");
        }

        // Invariant: default row cannot be deactivated
        if (cc.isDefault()) {
            throw new ConflictException(
                    "Cannot deactivate the default document currency '" + code.value()
                            + "'. Set another currency as default first.");
        }

        Long actorId = actorId();
        cc.setActive(false);
        cc.setUpdatedAt(Instant.now());
        cc.setUpdatedBy(actorId);
        return CompanyCurrencyDto.from(cc);
    }

    @Override
    public CompanyCurrencyDto setDefaultForCompany(Long companyId, String currencyCodeRaw) {
        CurrencyCode code = CurrencyCode.of(currencyCodeRaw);
        CompanyCurrency target = requireCompanyCurrencyActive(companyId, code);

        Long actorId = actorId();
        shiftCompanyDefault(companyId, target, actorId);
        return CompanyCurrencyDto.from(target);
    }

    // ── Branch-level management ───────────────────────────────────────────────

    @Override
    public BranchCurrencyDto enableForBranch(Long companyId, Long branchId,
                                              EnableCurrencyRequest request) {
        CurrencyCode code = CurrencyCode.of(request.currencyCode());

        // Subset rule: must be enabled+active in company_currency first
        assertEnabledForCompany(companyId, code);

        Long actorId = actorId();
        var existing = branchCurrencies.findByBranchIdAndCurrencyCode(branchId, code);
        BranchCurrency bc;
        if (existing.isPresent()) {
            bc = existing.get();
            bc.setActive(true);
            bc.setUpdatedAt(Instant.now());
            bc.setUpdatedBy(actorId);
        } else {
            bc = new BranchCurrency(companyId, branchId, code, false, true, actorId);
            bc = branchCurrencies.save(bc);
        }

        if (request.setAsDefault()) {
            shiftBranchDefault(branchId, bc, actorId);
        }
        return BranchCurrencyDto.from(bc);
    }

    @Override
    public BranchCurrencyDto deactivateForBranch(Long companyId, Long branchId,
                                                   String currencyCodeRaw) {
        CurrencyCode code = CurrencyCode.of(currencyCodeRaw);
        BranchCurrency bc = requireBranchCurrency(branchId, code);

        if (bc.isDefault()) {
            throw new ConflictException(
                    "Cannot deactivate the default document currency '" + code.value()
                            + "' for this branch. Set another currency as default first.");
        }

        Long actorId = actorId();
        bc.setActive(false);
        bc.setUpdatedAt(Instant.now());
        bc.setUpdatedBy(actorId);
        return BranchCurrencyDto.from(bc);
    }

    @Override
    public BranchCurrencyDto setDefaultForBranch(Long companyId, Long branchId,
                                                   String currencyCodeRaw) {
        CurrencyCode code = CurrencyCode.of(currencyCodeRaw);
        BranchCurrency target = requireBranchCurrencyActive(branchId, code);

        Long actorId = actorId();
        shiftBranchDefault(branchId, target, actorId);
        return BranchCurrencyDto.from(target);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void shiftCompanyDefault(Long companyId, CompanyCurrency newDefault, Long actorId) {
        // Clear the old default and flush immediately so the UPDATE reaches Postgres before the
        // UPDATE that sets the new default row — prevents a momentary two-row violation on the
        // partial unique index (FX-CURRENCY-019, FLOW-TAX-FX-052).
        companyCurrencies.findByCompanyIdAndIsDefaultTrue(companyId).ifPresent(old -> {
            if (old != newDefault) { // reference identity: JPA returns the same instance for the same row, so this is null-safe for unsaved rows
                old.setDefault(false);
                old.setUpdatedAt(Instant.now());
                old.setUpdatedBy(actorId);
                companyCurrencies.saveAndFlush(old);
            }
        });
        newDefault.setDefault(true);
        newDefault.setUpdatedAt(Instant.now());
        newDefault.setUpdatedBy(actorId);
    }

    private void shiftBranchDefault(Long branchId, BranchCurrency newDefault, Long actorId) {
        // Clear the old default and flush immediately so the UPDATE reaches Postgres before the
        // UPDATE that sets the new default row — prevents a momentary two-row violation on the
        // partial unique index (FX-CURRENCY-033).
        branchCurrencies.findByBranchIdAndIsDefaultTrue(branchId).ifPresent(old -> {
            if (old != newDefault) { // reference identity: JPA returns the same instance for the same row, so this is null-safe for unsaved rows
                old.setDefault(false);
                old.setUpdatedAt(Instant.now());
                old.setUpdatedBy(actorId);
                branchCurrencies.saveAndFlush(old);
            }
        });
        newDefault.setDefault(true);
        newDefault.setUpdatedAt(Instant.now());
        newDefault.setUpdatedBy(actorId);
    }

    private void assertGloballyActive(CurrencyCode code) {
        var cur = currencies.findByCode(code.value())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown currency: " + code.value()));
        if (!cur.isActive()) {
            throw new IllegalArgumentException(
                    CURRENCY_PREFIX + code.value() + "' is not globally active in the currencies master.");
        }
    }

    private void assertEnabledForCompany(Long companyId, CurrencyCode code) {
        companyCurrencies.findByCompanyIdAndCurrencyCodeAndActiveTrue(companyId, code)
                .orElseThrow(() -> new IllegalArgumentException(
                        CURRENCY_PREFIX + code.value()
                                + "' is not enabled for this company. Enable it at the company level first."));
    }

    private String requireBaseCurrency(Long companyId) {
        return companies.findById(companyId)
                .map(Company::getBaseCurrency)
                .orElseThrow(() -> {
                    log.debug("requireBaseCurrency: company id {} not found", companyId);
                    return new NotFoundException(MSG_COMPANY_NOT_FOUND);
                });
    }

    private CompanyCurrency requireCompanyCurrency(Long companyId, CurrencyCode code) {
        return companyCurrencies.findByCompanyIdAndCurrencyCode(companyId, code)
                .orElseThrow(() -> {
                    log.debug("requireCompanyCurrency: currency {} not found for company id {}",
                            code.value(), companyId);
                    return new NotFoundException(
                            CURRENCY_PREFIX + code.value() + "' is not configured for this company.");
                });
    }

    private CompanyCurrency requireCompanyCurrencyActive(Long companyId, CurrencyCode code) {
        CompanyCurrency cc = requireCompanyCurrency(companyId, code);
        if (!cc.isActive()) {
            throw new ConflictException(
                    CURRENCY_PREFIX + code.value() + "' is not active for this company."
                            + " Re-enable it first.");
        }
        return cc;
    }

    private BranchCurrency requireBranchCurrency(Long branchId, CurrencyCode code) {
        return branchCurrencies.findByBranchIdAndCurrencyCode(branchId, code)
                .orElseThrow(() -> {
                    log.debug("requireBranchCurrency: currency {} not found for branch id {}",
                            code.value(), branchId);
                    return new NotFoundException(
                            CURRENCY_PREFIX + code.value() + "' is not configured for this branch.");
                });
    }

    private BranchCurrency requireBranchCurrencyActive(Long branchId, CurrencyCode code) {
        BranchCurrency bc = requireBranchCurrency(branchId, code);
        if (!bc.isActive()) {
            throw new ConflictException(
                    CURRENCY_PREFIX + code.value() + "' is not active for this branch."
                            + " Re-enable it first.");
        }
        return bc;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
