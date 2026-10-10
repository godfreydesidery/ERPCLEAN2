package com.erp.modules.hr.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.service.CashDirectEntryService;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.dto.GlConfigDto;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.hr.domain.dto.RecordStatutoryPaymentRequest;
import com.erp.modules.hr.domain.dto.StatutoryLiabilityBalanceDto;
import com.erp.modules.hr.domain.enums.StatutoryLiability;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACC-07: records paying PAYE / NSSF / WCF / SDL / HESLB to the authority — DR the liability's
 * control account / CR the chosen cash or bank account — through
 * {@link CashDirectEntryService#recordSystemEntry}, the path net-wage disbursement already uses. The
 * control account comes from gl_config, never from the user.
 *
 * <p>What is owed is the control account's credit balance in the ledger (scalar SQL on
 * journal_lines, company-scoped — the hr module imports no GL entity beyond the account the
 * resolver returns). A payment above that balance is refused, so the account can never be pushed
 * into a debit by a mistyped amount.
 */
@Service
@Transactional
public class PayrollStatutoryPaymentServiceImpl implements PayrollStatutoryPaymentService {

    private final GLConfigResolver       glConfig;
    private final GlConfigService        glConfigs;
    private final CashDirectEntryService cashEntries;
    private final CompanyRepository      companies;
    private final JdbcTemplate           jdbc;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;

    public PayrollStatutoryPaymentServiceImpl(GLConfigResolver glConfig,
                                              GlConfigService glConfigs,
                                              CashDirectEntryService cashEntries,
                                              CompanyRepository companies,
                                              JdbcTemplate jdbc,
                                              ScopeGuard scopeGuard,
                                              AuditService audit) {
        this.glConfig    = glConfig;
        this.glConfigs   = glConfigs;
        this.cashEntries = cashEntries;
        this.companies   = companies;
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.audit       = audit;
    }

    static GlConfigKey accountKey(StatutoryLiability liability) {
        return switch (liability) {
            case PAYE  -> GlConfigKey.PAYE_PAYABLE;
            case NSSF  -> GlConfigKey.NSSF_PAYABLE;
            case WCF   -> GlConfigKey.WCF_PAYABLE;
            case SDL   -> GlConfigKey.SDL_PAYABLE;
            case HESLB -> GlConfigKey.HESLB_PAYABLE;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatutoryLiabilityBalanceDto> outstanding() {
        Long companyId = companyId();
        // The mappings via the non-throwing list, not resolve(): a missing mapping thrown inside
        // this transaction would mark it rollback-only. A company without payroll accounts simply
        // lists fewer liabilities.
        Map<GlConfigKey, GlConfigDto> mapped = new java.util.EnumMap<>(GlConfigKey.class);
        glConfigs.list(companyId).forEach(c -> mapped.put(c.configKey(), c));
        List<StatutoryLiabilityBalanceDto> out = new ArrayList<>();
        for (StatutoryLiability l : StatutoryLiability.values()) {
            GlConfigDto c = mapped.get(accountKey(l));
            if (c == null || c.accountId() == null) {
                continue;
            }
            out.add(new StatutoryLiabilityBalanceDto(l, c.accountCode(), c.accountName(),
                    creditBalance(companyId, c.accountId())));
        }
        return out;
    }

    @Override
    public CashTransactionDto pay(RecordStatutoryPaymentRequest req) {
        Long companyId = companyId();
        ChartOfAccount acct = glConfig.resolve(companyId, accountKey(req.liability()));
        BigDecimal owed = creditBalance(companyId, acct.getId());
        if (req.amount().compareTo(owed) > 0) {
            throw new IllegalArgumentException("The payment is more than the "
                    + req.liability().name() + " still owed (" + owed.toPlainString() + ").");
        }
        // findScopedById: companyId came from the caller's own scope-checked context.
        String companyUid = companies.findScopedById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found."))
                .getUid();
        String reference = req.reference() != null && !req.reference().isBlank()
                ? req.reference().trim() : null;
        CashTransactionDto txn = cashEntries.recordSystemEntry(new RecordDirectEntryRequest(
                companyUid, req.cashBankAccountUid(), CashTxnDirection.OUT, req.amount(),
                req.paymentDate(), acct.getUid(),
                req.liability().name() + " payment" + (reference != null ? " - " + reference : "")),
                null);

        audit.record(AuditEvent.of(AuditActions.HR_STATUTORY_PAY, "cash_transactions",
                        txn.id(), txn.uid())
                .detail(Map.of("liability", req.liability().name(),
                        "amount", req.amount().toPlainString())));
        return txn;
    }

    private Long companyId() {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p != null ? p.companyId() : null;
        if (companyId == null) {
            throw new NotFoundException("Company not found.");
        }
        scopeGuard.assertCanActIn(p, companyId);
        return companyId;
    }

    /** Credit-minus-debit on the account across every posted journal of the company. */
    private BigDecimal creditBalance(Long companyId, Long accountId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(credit_amount - debit_amount), 0)
                FROM journal_lines
                WHERE company_id = ? AND account_id = ?
                """, BigDecimal.class, companyId, accountId);
        return v != null ? v : BigDecimal.ZERO;
    }
}
