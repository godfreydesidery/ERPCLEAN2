package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.domain.entity.PettyCashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.AccountingSetupException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyConversionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts petty-cash movements to the GL (ARC-10 / ACC-12 — the GL fast-follow of ADR-0050 D-7.1).
 *
 * <ul>
 *   <li><b>DISBURSEMENT</b>: DR the expense account captured on the voucher / CR Petty Cash.</li>
 *   <li><b>REPLENISHMENT</b>: DR Petty Cash / CR the source: a cash/bank account (its linked GL
 *       account, plus an OUT row in that account's cash book) or, failing that, the captured
 *       funding GL account; neither given = the company's default cash/bank account.</li>
 *   <li><b>ADJUSTMENT</b>: a counted surplus is DR Petty Cash / CR Cash Over; a shortage is DR Cash
 *       Short / CR Petty Cash — the same over/short accounts a till variance uses — so the GL
 *       petty-cash balance keeps following the fund balance.</li>
 * </ul>
 *
 * <p><b>Petty Cash GL account.</b> A fund has no GL column and gl_configs has no PETTY_CASH key
 * (both would need a migration), so every fund of a company posts to one asset account named
 * "Petty Cash" — an existing one, else code {@value #PETTY_CASH_CODE}, else the first free code in
 * 1011..1019, created on first use (see {@link #pettyCashAccount}). It is never an account a till
 * or bank already posts to: petty cash moving through a till's GL account (1000 Cash, or a separate
 * till account such as 1010) would put that till's cash book and its GL account out of step.
 *
 * <p>Runs inside the petty-cash command's transaction: a GL refusal (closed period, missing setup)
 * rolls back the whole movement. Historic petty-cash rows are never posted retroactively.
 */
@Component
public class PettyCashGlPoster {

    private static final Logger log = LoggerFactory.getLogger(PettyCashGlPoster.class);

    static final String PETTY_CASH_CODE = "1010";
    static final String PETTY_CASH_NAME = "Petty Cash";

    /** Control accounts owned by a sub-ledger engine: a petty-cash voucher must not hit them. */
    private static final Set<ControlType> ENGINE_OWNED = Set.of(
            ControlType.AR, ControlType.AP, ControlType.INVENTORY, ControlType.TAX,
            ControlType.PAYROLL_CLEARING, ControlType.FX_CLEARING);

    private final ChartOfAccountRepository glAccounts;
    private final CashBankAccountRepository cashAccounts;
    private final CashBankAccountResolver cashAccountResolver;
    private final CashTransactionRecorder cashTxnRecorder;
    private final GLConfigResolver glConfig;
    private final GLPostingService glPosting;
    private final CompanyRepository companies;
    private final CurrencyConversionService fxConversion;

    public PettyCashGlPoster(ChartOfAccountRepository glAccounts,
                             CashBankAccountRepository cashAccounts,
                             CashBankAccountResolver cashAccountResolver,
                             CashTransactionRecorder cashTxnRecorder,
                             GLConfigResolver glConfig,
                             GLPostingService glPosting,
                             CompanyRepository companies,
                             CurrencyConversionService fxConversion) {
        this.glAccounts          = glAccounts;
        this.cashAccounts        = cashAccounts;
        this.cashAccountResolver = cashAccountResolver;
        this.cashTxnRecorder     = cashTxnRecorder;
        this.glConfig            = glConfig;
        this.glPosting           = glPosting;
        this.companies           = companies;
        this.fxConversion        = fxConversion;
    }

    /**
     * What a movement posts against, resolved and validated BEFORE it is saved.
     *
     * @param pettyCashGlAccountId the Petty Cash asset account
     * @param counterGlAccountId   the other side: expense, funding, or over/short account
     * @param cashBankAccountId    the cash/bank account whose cash book moves too (null if none)
     * @param debitPettyCash       true when petty cash is debited (money comes in)
     */
    public record Plan(Long pettyCashGlAccountId, Long counterGlAccountId, Long cashBankAccountId,
                       boolean debitPettyCash) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Plan plan(PettyCashFund fund, PettyCashTxnType type, BigDecimal signedAmount,
                     Long capturedGlAccountId, String sourceCashBankAccountUid) {
        Long companyId = fund.getCompanyId();
        ChartOfAccount petty = pettyCashAccount(companyId);
        return switch (type) {
            case DISBURSEMENT -> {
                if (capturedGlAccountId == null) {
                    throw new IllegalArgumentException(
                            "Choose the expense account this money was spent on.");
                }
                ChartOfAccount expense = usable(companyId, capturedGlAccountId, petty);
                Long linkedCash = linkedCashAccountId(companyId, expense.getId());
                yield new Plan(petty.getId(), expense.getId(), linkedCash, false);
            }
            case REPLENISHMENT -> {
                if (sourceCashBankAccountUid != null && !sourceCashBankAccountUid.isBlank()) {
                    CashAccountGlResolutionDto src =
                            cashAccountResolver.resolve(companyId, sourceCashBankAccountUid);
                    if (src.glAccountId().equals(petty.getId())) {
                        throw new IllegalArgumentException(
                                "Choose the cash or bank account the money came from, not petty cash.");
                    }
                    yield new Plan(petty.getId(), src.glAccountId(), src.cashBankAccountId(), true);
                }
                if (capturedGlAccountId != null) {
                    ChartOfAccount funding = usable(companyId, capturedGlAccountId, petty);
                    yield new Plan(petty.getId(), funding.getId(),
                            linkedCashAccountId(companyId, funding.getId()), true);
                }
                CashAccountGlResolutionDto dflt = cashAccountResolver.resolve(companyId, null);
                yield new Plan(petty.getId(), dflt.glAccountId(), dflt.cashBankAccountId(), true);
            }
            case ADJUSTMENT -> {
                boolean surplus = signedAmount.signum() > 0;
                ChartOfAccount variance = glConfig.resolve(companyId,
                        surplus ? GlConfigKey.POS_CASH_OVER : GlConfigKey.POS_CASH_SHORT);
                yield new Plan(petty.getId(), variance.getId(), null, surplus);
            }
        };
    }

    /**
     * Posts the planned journal for a saved movement, writes the source account's cash-book row
     * when there is one, and returns the journal entry uid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(PettyCashFund fund, PettyCashTransaction txn, Plan plan, Long actorId) {
        Long companyId = fund.getCompanyId();
        String baseCurrency = companies.findScopedById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");
        BigDecimal face = txn.getAmount().abs();
        String fundCurrency = fund.getCurrency().value();
        BigDecimal base = fundCurrency.equals(baseCurrency)
                ? face
                : fxConversion.toBase(face, fundCurrency, companyId, txn.getTxnDate()).baseAmount();

        String memo = describe(fund, txn);
        LineDraft pettyLine = plan.debitPettyCash()
                ? new LineDraft(plan.pettyCashGlAccountId(), base, BigDecimal.ZERO, baseCurrency, memo)
                : new LineDraft(plan.pettyCashGlAccountId(), BigDecimal.ZERO, base, baseCurrency, memo);
        LineDraft counterLine = plan.debitPettyCash()
                ? new LineDraft(plan.counterGlAccountId(), BigDecimal.ZERO, base, baseCurrency, memo)
                : new LineDraft(plan.counterGlAccountId(), base, BigDecimal.ZERO, baseCurrency, memo);
        List<LineDraft> lines = plan.debitPettyCash()
                ? List.of(pettyLine, counterLine)
                : List.of(counterLine, pettyLine);

        JournalEntryDto posted = glPosting.post(new JournalEntryDraft(
                companyId, fund.getBranchId(), txn.getTxnDate(), memo,
                JournalSourceType.CASH_DIRECT, txn.getUid(), null, actorId, lines));

        if (plan.cashBankAccountId() != null) {
            // Money into petty cash leaves the source account (OUT); a voucher "spent" into a
            // cash/bank account is money arriving there (IN).
            CashTxnDirection direction = plan.debitPettyCash()
                    ? CashTxnDirection.OUT : CashTxnDirection.IN;
            cashTxnRecorder.recordPettyCashFunding(companyId, fund.getBranchId(),
                    plan.cashBankAccountId(), direction, face, fundCurrency,
                    plan.pettyCashGlAccountId(), txn.getUid(), posted.uid(), txn.getTxnDate(),
                    memo, actorId);
        }
        return posted.uid();
    }

    // -------------------------------------------------------------------------

    /**
     * The company's Petty Cash asset account (see class javadoc), resolved without ever borrowing
     * an account that a cash/bank account (a till or a bank) already posts to — that would break the
     * till's cash-book / GL tie-out (ARC-01 told companies to give each till its own GL account,
     * often 1010):
     * <ol>
     *   <li>an active ASSET account named "Petty Cash" (any code, lowest code first);</li>
     *   <li>else code 1010, when its name says petty cash;</li>
     *   <li>else a new "Petty Cash" account on the first free code in 1011..1019.</li>
     * </ol>
     * Every candidate must be unlinked to any cash_bank_account.
     */
    private ChartOfAccount pettyCashAccount(Long companyId) {
        java.util.Optional<ChartOfAccount> named = glAccounts
                .findByCompanyIdAndAccountTypeIn(companyId, List.of(AccountType.ASSET)).stream()
                .filter(a -> companyId.equals(a.getCompanyId()))
                .filter(ChartOfAccount::isActive)
                .filter(a -> a.getName() != null
                        && PETTY_CASH_NAME.equalsIgnoreCase(a.getName().trim()))
                .filter(a -> !isTillAccount(companyId, a.getId()))
                .min(java.util.Comparator.comparing(ChartOfAccount::getAccountCode));
        if (named.isPresent()) {
            return named.get();
        }

        ChartOfAccount at1010 = glAccounts.findByCompanyIdAndAccountCode(companyId, PETTY_CASH_CODE)
                .orElse(null);
        if (at1010 != null && at1010.isActive() && at1010.getAccountType() == AccountType.ASSET
                && at1010.getName() != null
                && at1010.getName().toLowerCase(java.util.Locale.ROOT).contains("petty")
                && !isTillAccount(companyId, at1010.getId())) {
            return at1010;
        }
        if (at1010 == null) {
            return create(companyId, PETTY_CASH_CODE);
        }
        for (int code = 1011; code <= 1019; code++) {
            String c = String.valueOf(code);
            if (!glAccounts.existsByCompanyIdAndAccountCode(companyId, c)) {
                return create(companyId, c);
            }
        }
        throw new AccountingSetupException(
                "Petty cash needs its own asset account named \"Petty Cash\", and codes 1010 to 1019"
                        + " are all taken. Create an asset account named Petty Cash under General"
                        + " Ledger, Chart of Accounts, then try again.");
    }

    private ChartOfAccount create(Long companyId, String code) {
        ChartOfAccount acct = new ChartOfAccount(companyId, code, PETTY_CASH_NAME,
                AccountType.ASSET, null);
        acct.setControlType(ControlType.CASH);
        acct = glAccounts.save(acct);
        log.info("PettyCashGlPoster: created GL account {} {} for company {}",
                code, PETTY_CASH_NAME, companyId);
        return acct;
    }

    /** True when a cash/bank account (till or bank) already posts to this GL account. */
    private boolean isTillAccount(Long companyId, Long glAccountId) {
        return glAccountId != null
                && cashAccounts.findByCompanyIdAndGlAccountId(companyId, glAccountId).isPresent();
    }

    /** A captured expense/funding account, inside the company, that a voucher may post to. */
    private ChartOfAccount usable(Long companyId, Long glAccountId, ChartOfAccount petty) {
        // The id was resolved from a uid inside this company by the caller; re-checked here.
        ChartOfAccount acct = glAccounts.findScopedById(glAccountId)
                .filter(a -> companyId.equals(a.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("GL account not found."));
        if (acct.getId().equals(petty.getId())) {
            throw new IllegalArgumentException(
                    "Choose another account: petty cash cannot be paid into or out of itself.");
        }
        if (!acct.isActive()) {
            throw new IllegalArgumentException("The chosen account " + acct.getAccountCode()
                    + " is inactive. Choose an active account.");
        }
        if (acct.getControlType() != null && ENGINE_OWNED.contains(acct.getControlType())) {
            throw new IllegalArgumentException("Account " + acct.getAccountCode()
                    + " is kept by another part of the system and cannot take a petty-cash voucher."
                    + " Choose an expense account.");
        }
        return acct;
    }

    private Long linkedCashAccountId(Long companyId, Long glAccountId) {
        return cashAccounts.findByCompanyIdAndGlAccountId(companyId, glAccountId)
                .map(a -> a.getId()).orElse(null);
    }

    private static String describe(PettyCashFund fund, PettyCashTransaction txn) {
        String what = switch (txn.getTxnType()) {
            case DISBURSEMENT -> "Petty cash paid out";
            case REPLENISHMENT -> "Petty cash top-up";
            case ADJUSTMENT -> "Petty cash count adjustment";
        };
        String text = what + " " + txn.getTxnNumber() + " (" + fund.getName() + ")";
        if (txn.getDescription() != null && !txn.getDescription().isBlank()) {
            text += " - " + txn.getDescription().trim();
        }
        return text.length() <= 255 ? text : text.substring(0, 255);
    }
}
