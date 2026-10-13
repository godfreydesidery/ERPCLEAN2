package com.erp.modules.tax.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.service.CashDirectEntryService;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACC-07: books paying a tax liability to TRA — DR the liability control account (VAT Due,
 * WHT Payable) / CR the chosen cash or bank account — as a cash transaction, through
 * {@link CashDirectEntryService#recordSystemEntry}, the path payroll's net-wage disbursement uses.
 *
 * <p>The liability account comes from gl_config, never from the user, so the user-facing
 * control-account guards of a direct entry (which exist to stop a USER corrupting a sub-ledger) do
 * not apply; tenancy, active-account, open-period and balanced-posting checks all still do.
 */
@Service
public class TaxPaymentPoster {

    private final CashDirectEntryService cashEntries;
    private final GLConfigResolver       glConfig;
    private final CompanyRepository      companies;

    public TaxPaymentPoster(CashDirectEntryService cashEntries, GLConfigResolver glConfig,
                            CompanyRepository companies) {
        this.cashEntries = cashEntries;
        this.glConfig    = glConfig;
        this.companies   = companies;
    }

    /**
     * Pay {@code amount} (base currency) of the {@code liability} account out of the cash/bank
     * account {@code cashBankAccountUid}. Must run inside the caller's transaction so the payment and
     * the document it settles commit together. The caller has already scope-checked
     * {@code companyId}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CashTransactionDto pay(Long companyId, GlConfigKey liability, String cashBankAccountUid,
                                  BigDecimal amount, LocalDate paymentDate, String memo) {
        // findScopedById: companyId was scope-asserted by the caller — a self-scope lookup.
        String companyUid = companies.findScopedById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found."))
                .getUid();
        String liabilityUid = glConfig.resolve(companyId, liability).getUid();
        return cashEntries.recordSystemEntry(new RecordDirectEntryRequest(
                companyUid, cashBankAccountUid, CashTxnDirection.OUT, amount, paymentDate,
                liabilityUid, memo), null);
    }
}
