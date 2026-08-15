package com.erp.modules.sales.service;

import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.repository.PosTillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a brand-new tenant's first POS till on its default branch, from a name the operator
 * supplied (ADR-0062 P5-2).
 *
 * <p>Named {@code createIfNamed} rather than {@code seedDefaults} deliberately — see
 * {@code DefaultPriceListProvisioner} for why the seeder shape is the one thing this must not have.
 */
@Component
public class PosTillProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PosTillProvisioner.class);

    private final CashBankAccountRepository cashAccounts;
    private final PosTillRepository tills;
    private final SalesDepthNumberGenerator numberGenerator;

    public PosTillProvisioner(CashBankAccountRepository cashAccounts,
                              PosTillRepository tills,
                              SalesDepthNumberGenerator numberGenerator) {
        this.cashAccounts = cashAccounts;
        this.tills = tills;
        this.numberGenerator = numberGenerator;
    }

    /**
     * Creates the till when the operator named one, and quietly creates nothing when it cannot.
     *
     * <p><b>Never throws for a missing drawer account, and that is the point.</b>
     * {@code pos_tills.cash_bank_account_id} is NOT NULL behind a foreign key, and the only thing
     * that creates that account is {@code CashBankSeeder} — which skips a company whose CASH
     * gl_config is missing. Inserting anyway would raise a constraint violation that rolls back the
     * WHOLE tenant-creation transaction: the operator would get no tenant at all because an
     * optional convenience could not be created. {@code PettyCashFundSeeder} faces the identical
     * NOT NULL shape and answers it the same way — resolve, and return without inserting if the
     * target is absent.
     *
     * <p>Logged at WARN rather than that seeder's DEBUG, because there is no later call site to
     * finish the job: the operator asked for a till and will not otherwise learn they did not get
     * one.
     *
     * @param defaultPriceListId the price list created earlier in this same transaction, or null.
     *                           Recorded because the column exists for it; note that nothing reads
     *                           {@code default_price_list_id} today — POS pricing resolves through
     *                           the COMPANY default price list, not the till's.
     * @return the new till's id, or {@code null} when no name was supplied or no drawer account
     *         exists
     */
    @Transactional
    public Long createIfNamed(Long companyId, Long branchId, String name, Long defaultPriceListId) {
        // Guarded here and not only at the call site: bootstrap reaches this method with a null,
        // and that path must be provably inert without reading the caller.
        if (name == null || name.isBlank()) {
            return null;
        }

        // Same resolution order as PosTillServiceImpl.resolveCashAccount, so the two paths cannot
        // disagree about what "the drawer account" means — minus its terminal throw, which is right
        // for a human standing at the till screen and wrong here.
        var account = cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId)
                .or(() -> cashAccounts.findByCompanyIdAndActive(companyId, true).stream().findFirst());
        if (account.isEmpty()) {
            log.warn("Provisioned company {} WITHOUT the requested POS till: the company has no "
                    + "cash/bank account, so the drawer account could not be set. Create a cash "
                    + "account, then add the till on the POS till screen.", companyId);
            return null;
        }

        PosTill till = new PosTill(companyId, branchId, name.strip(), account.get().getId(), null);
        // A till with a blank code reads as a broken field on the open-shift card (busy-day sim).
        till.setCode(numberGenerator.nextPosTill(companyId));
        till.setDefaultPriceListId(defaultPriceListId);
        tills.save(till);

        log.info("Provisioned POS till '{}' for company {} on branch {}.",
                till.getCode(), companyId, branchId);
        return till.getId();
    }
}
