package com.erp.modules.tax.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.tax.domain.dto.WhtCaptureResultDto;
import com.erp.modules.tax.domain.entity.WhtTransaction;
import com.erp.modules.tax.domain.entity.WhtType;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTransactionRepository;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WHT capture service (ADR-0017 D-9). Records the certificate + resolves the GL account;
 * GL posting itself happens in the calling AR/AP service's JournalEntryDraft.
 */
@Service
@Transactional
public class WhtCaptureServiceImpl implements WhtCaptureService {

    private final WhtTypeRepository        whtTypes;
    private final WhtTransactionRepository whtTransactions;
    private final WhtNumberGenerator       numberGen;
    private final GLConfigResolver         glConfig;
    private final ScopeGuard              scopeGuard;
    private final AuditService            audit;

    public WhtCaptureServiceImpl(WhtTypeRepository whtTypes,
                                  WhtTransactionRepository whtTransactions,
                                  WhtNumberGenerator numberGen,
                                  GLConfigResolver glConfig,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.whtTypes        = whtTypes;
        this.whtTransactions = whtTransactions;
        this.numberGen       = numberGen;
        this.glConfig        = glConfig;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public WhtCaptureResultDto captureOnPayment(Long companyId, Long branchId,
                                                 String whtTypeUid,
                                                 Long partySupplierId, String partyName,
                                                 String partyTin,
                                                 String apPaymentUid,
                                                 BigDecimal taxableBase, BigDecimal whtAmount,
                                                 String currency, LocalDate certificateDate,
                                                 String journalEntryUid, Long actorId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        WhtType whtType = resolveType(companyId, whtTypeUid, WhtKind.WHT_ON_PAYMENT);
        ChartOfAccount glAccount = glConfig.resolve(companyId, GlConfigKey.WHT_PAYABLE);

        return capture(companyId, branchId, whtType, "SUPPLIER",
                partySupplierId, partyName, partyTin, apPaymentUid,
                taxableBase, whtAmount, currency, certificateDate,
                journalEntryUid, actorId, glAccount);
    }

    @Override
    public WhtCaptureResultDto captureOnReceipt(Long companyId, Long branchId,
                                                 String whtTypeUid,
                                                 Long partyCustomerId, String partyName,
                                                 String partyTin,
                                                 String arReceiptUid,
                                                 BigDecimal taxableBase, BigDecimal whtAmount,
                                                 String currency, LocalDate certificateDate,
                                                 String journalEntryUid, Long actorId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        WhtType whtType = resolveType(companyId, whtTypeUid, WhtKind.WHT_ON_RECEIPT);
        ChartOfAccount glAccount = glConfig.resolve(companyId, GlConfigKey.WHT_RECEIVABLE);

        return capture(companyId, branchId, whtType, "CUSTOMER",
                partyCustomerId, partyName, partyTin, arReceiptUid,
                taxableBase, whtAmount, currency, certificateDate,
                journalEntryUid, actorId, glAccount);
    }

    @Override
    public void linkJournalEntry(String whtTransactionUid, String journalEntryUid) {
        whtTransactions.findByUid(whtTransactionUid).ifPresent(txn -> {
            txn.setJournalEntryRef(journalEntryUid);
            whtTransactions.save(txn);
        });
    }

    // -------------------------------------------------------------------------

    private WhtCaptureResultDto capture(Long companyId, Long branchId,
                                         WhtType whtType, String partyKind,
                                         Long partyId, String partyName,
                                         String partyTin,
                                         String sourceRef,
                                         BigDecimal taxableBase, BigDecimal whtAmount,
                                         String currency, LocalDate certificateDate,
                                         String journalEntryUid, Long actorId,
                                         ChartOfAccount glAccount) {
        String certNumber = numberGen.next(companyId);

        WhtTransaction txn = new WhtTransaction(
                companyId, branchId, certNumber,
                whtType.getId(), whtType.getKind(),
                partyKind, partyId, partyName,
                sourceRef, taxableBase, whtAmount,
                currency, certificateDate,
                partyTin, whtType.getRatePct(),
                actorId);

        if (journalEntryUid != null) {
            txn.setJournalEntryRef(journalEntryUid);
        }
        txn = whtTransactions.save(txn);

        audit.record(AuditEvent.of(AuditActions.WHT_CAPTURE, "wht_transactions",
                txn.getId(), txn.getUid())
                .detail(Map.of("certNumber", certNumber,
                        "kind", whtType.getKind().name(),
                        "sourceRef", sourceRef,
                        "whtAmount", whtAmount.toPlainString())));

        return new WhtCaptureResultDto(glAccount.getId(), txn.getUid(), certNumber);
    }

    private WhtType resolveType(Long companyId, String whtTypeUid, WhtKind expectedKind) {
        WhtType whtType = whtTypes.findByUid(whtTypeUid)
                .orElseThrow(() -> new NotFoundException("WHT type not found."));
        if (!whtType.getCompanyId().equals(companyId)) {
            throw new IllegalStateException(
                    "WHT type does not belong to this company.");
        }
        if (!whtType.isActive()) {
            throw new IllegalStateException(
                    "WHT type is inactive.");
        }
        if (whtType.getKind() != expectedKind) {
            throw new IllegalStateException(
                    "WhtType " + whtTypeUid + " has kind " + whtType.getKind()
                    + " but expected " + expectedKind + ".");
        }
        return whtType;
    }
}
