package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.repository.PettyCashFundRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Seeds one default ACTIVE "Petty Cash" fund per company (ADR-0050 D-7 PR-B, D-7.4/scope).
 * Idempotent — skips if a fund with the default code already exists for the company.
 *
 * <p><b>Why this is called from three places, not just {@code CompanyProvisioningServiceImpl}:</b>
 * {@code petty_cash_funds.branch_id} is {@code NOT NULL} (ADR-0050 D-7.5), but {@code
 * CompanyProvisioningServiceImpl.provisionDefaults} runs BEFORE any branch exists for a
 * freshly-created company — both {@code BootstrapRunner} and {@code CompanyServiceImpl.create}
 * provision company defaults, then create the (first/default) branch afterwards. So:
 * <ul>
 *   <li>{@code CompanyProvisioningServiceImpl.provisionDefaults} calls this seeder per the standard
 *       per-company-default wiring; for a brand-new company it is a safe no-op (no branch yet), but
 *       it DOES seed the fund when re-provisioning an existing company that already has a branch
 *       ({@code CompanyServiceImpl.reprovisionDefaults}).</li>
 *   <li>{@code BootstrapRunner} and {@code BranchServiceImpl.create} additionally call this seeder
 *       right after a branch actually exists — mirroring the branch-scoped {@code
 *       StockLocationSeeder} precedent (deliberately excluded from {@code
 *       CompanyProvisioningServiceImpl} for the identical reason).</li>
 * </ul>
 * All three call sites are idempotent (guarded by {@link PettyCashFundRepository#existsByCompanyIdAndCode}),
 * so calling from more than one place is safe.
 */
@Component
public class PettyCashFundSeeder {

    private static final Logger log = LoggerFactory.getLogger(PettyCashFundSeeder.class);

    /** Default fund code + name for the per-company seed. */
    private static final String DEFAULT_CODE = "PETTY";
    private static final String DEFAULT_NAME = "Petty Cash";

    private final PettyCashFundRepository funds;
    private final CompanyRepository       companies;
    private final BranchRepository        branches;

    public PettyCashFundSeeder(PettyCashFundRepository funds, CompanyRepository companies,
                                BranchRepository branches) {
        this.funds     = funds;
        this.companies = companies;
        this.branches  = branches;
    }

    /**
     * Seeds the default fund for {@code companyId} if one does not already exist. Defers (no-op,
     * logged at DEBUG) when the company has no branch yet — {@code branch_id} is {@code NOT NULL}
     * on {@code petty_cash_funds}, so a later call (once a branch exists) completes the seed.
     */
    @Transactional
    public void seedDefaults(Long companyId) {
        if (funds.existsByCompanyIdAndCode(companyId, DEFAULT_CODE)) {
            log.debug("PettyCashFundSeeder: default fund already exists for company {}.", companyId);
            return;
        }

        var branchOpt = branches.findByCompanyIdAndIsDefaultTrue(companyId);
        if (branchOpt.isEmpty()) {
            log.debug("PettyCashFundSeeder: no default branch yet for company {} — deferring "
                    + "(seeded once a branch exists).", companyId);
            return;
        }
        Long branchId = branchOpt.get().getId();

        String currency = companies.findScopedById(companyId)
                .map(Company::getBaseCurrency)
                .orElse("TZS");

        PettyCashFund fund = new PettyCashFund(companyId, branchId, DEFAULT_CODE, DEFAULT_NAME,
                null, BigDecimal.ZERO, currency, null);
        funds.save(fund);

        log.info("PettyCashFundSeeder: seeded default fund '{}' for company {} (branch {}).",
                DEFAULT_CODE, companyId, branchId);
    }
}
