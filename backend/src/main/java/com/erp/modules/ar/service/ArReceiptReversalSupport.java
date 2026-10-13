package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.entity.ArReceiptAllocation;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sub-ledger half of undoing an AR receipt, shared by the bounced-cheque reversal
 * ({@code ChequeBounceReversalHandler}) and the manual "reverse receipt" command (ARC-04).
 *
 * <p>Called only AFTER the receipt's journal has been reversed in the GL. It restores the invoice
 * outstanding the receipt's allocations relieved (face + base), zeroes the on-account remainder
 * (the GL reversal returned the whole receipt to AR-control, so leaving a remainder would keep
 * netting it off the sub-ledger) and stamps {@code reversed_at}. Allocation rows are kept as
 * history; every reader that nets receipts already skips reversed ones.
 */
@Component
public class ArReceiptReversalSupport {

    private final ArReceiptRepository receipts;
    private final ArReceiptAllocationRepository allocations;
    private final ArInvoiceRepository invoices;
    private final CompanyRepository companies;

    public ArReceiptReversalSupport(ArReceiptRepository receipts,
                                    ArReceiptAllocationRepository allocations,
                                    ArInvoiceRepository invoices,
                                    CompanyRepository companies) {
        this.receipts    = receipts;
        this.allocations = allocations;
        this.invoices    = invoices;
        this.companies   = companies;
    }

    /** Restores the relieved invoices and marks the receipt reversed. Same TX as the caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ArReceipt restoreAndMarkReversed(ArReceipt receipt, Long actorId) {
        Long companyId = receipt.getCompanyId();
        int baseScale = baseMinorUnits(companies.findScopedById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS"));
        List<ArReceiptAllocation> allocs = allocations.findByReceiptId(receipt.getId());
        for (ArReceiptAllocation alloc : allocs) {
            invoices.findByCompanyIdAndId(companyId, alloc.getArInvoiceId()).ifPresent(inv -> {
                inv.setOutstandingAmount(inv.getOutstandingAmount().add(alloc.getAllocatedAmount()));
                if (alloc.getBaseAllocatedAmount() != null) {
                    BigDecimal invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = alloc.getAllocatedAmount()
                            .multiply(invoiceRate).setScale(baseScale, RoundingMode.HALF_UP);
                    BigDecimal newBase = (inv.getBaseOutstandingAmount() != null
                            ? inv.getBaseOutstandingAmount() : BigDecimal.ZERO)
                            .add(baseRelievedRestored);
                    BigDecimal cap = inv.getBaseOriginalAmount() != null
                            ? inv.getBaseOriginalAmount() : inv.getOriginalAmount();
                    inv.setBaseOutstandingAmount(newBase.min(cap));
                }
                inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
                inv.setUpdatedAt(Instant.now());
                if (actorId != null) {
                    inv.setUpdatedBy(actorId);
                }
                invoices.save(inv);
            });
        }

        receipt.setUnallocatedAmount(BigDecimal.ZERO);
        receipt.setReversedAt(Instant.now());
        receipt.setUpdatedAt(Instant.now());
        if (actorId != null) {
            receipt.setUpdatedBy(actorId);
        }
        return receipts.save(receipt);
    }

    private static ArInvoiceStatus deriveInvoiceStatus(BigDecimal outstanding, BigDecimal original) {
        if (outstanding.compareTo(BigDecimal.ZERO) == 0) return ArInvoiceStatus.PAID;
        if (outstanding.compareTo(original) < 0) return ArInvoiceStatus.PARTIAL;
        return ArInvoiceStatus.OPEN;
    }

    private static int baseMinorUnits(String currencyCode) {
        if (currencyCode == null) return 2;
        return switch (currencyCode) {
            case "TZS", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR" -> 3;
            default -> 2;
        };
    }
}
