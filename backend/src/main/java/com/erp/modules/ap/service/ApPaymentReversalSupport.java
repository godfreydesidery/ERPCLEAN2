package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sub-ledger half of undoing an AP payment, shared by the bounced outbound cheque
 * ({@code ChequeBouncePaymentReversalHandler}) and the "reverse payment" command (AP-03).
 *
 * <p>Called only AFTER the payment's journal has been reversed in the GL. It restores the bill
 * outstanding the payment's allocations relieved (face + base), re-derives each bill's status
 * (PAID / PARTIALLY_PAID, or MATCHED — payable again — when nothing is paid any more), zeroes the
 * on-account remainder (the GL reversal put the whole payment back on AP control) and stamps
 * {@code reversed_at}. Allocation rows stay as history.
 */
@Component
public class ApPaymentReversalSupport {

    private final ApPaymentRepository payments;
    private final ApPaymentAllocationRepository allocations;
    private final SupplierBillRepository bills;
    private final CompanyRepository companies;

    public ApPaymentReversalSupport(ApPaymentRepository payments,
                                    ApPaymentAllocationRepository allocations,
                                    SupplierBillRepository bills,
                                    CompanyRepository companies) {
        this.payments    = payments;
        this.allocations = allocations;
        this.bills       = bills;
        this.companies   = companies;
    }

    /** Restores the relieved bills and marks the payment reversed. Same TX as the caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApPayment restoreAndMarkReversed(ApPayment payment, Long actorId) {
        Long companyId = payment.getCompanyId();
        int baseScale = baseMinorUnits(companies.findScopedById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS"));
        List<ApPaymentAllocation> allocs = allocations.findByApPaymentId(payment.getId());
        for (ApPaymentAllocation alloc : allocs) {
            bills.findByCompanyIdAndId(companyId, alloc.getSupplierBillId()).ifPresent(bill -> {
                bill.setOutstandingAmount(bill.getOutstandingAmount().add(alloc.getAllocatedAmount()));
                if (alloc.getBaseAllocatedAmount() != null) {
                    BigDecimal billRate = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = alloc.getAllocatedAmount()
                            .multiply(billRate).setScale(baseScale, RoundingMode.HALF_UP);
                    BigDecimal newBase = (bill.getBaseOutstandingAmount() != null
                            ? bill.getBaseOutstandingAmount() : BigDecimal.ZERO)
                            .add(baseRelievedRestored);
                    BigDecimal cap = bill.getBaseGrossAmount() != null
                            ? bill.getBaseGrossAmount() : bill.getGrossAmount();
                    bill.setBaseOutstandingAmount(newBase.min(cap));
                }
                bill.setStatus(statusAfterRestore(bill.getOutstandingAmount(), bill.getGrossAmount()));
                bill.setUpdatedAt(Instant.now());
                bills.save(bill);
            });
        }

        payment.setUnallocatedAmount(BigDecimal.ZERO);
        payment.setReversedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        if (actorId != null) {
            payment.setUpdatedBy(actorId);
        }
        return payments.save(payment);
    }

    static SupplierBillStatus statusAfterRestore(BigDecimal outstanding, BigDecimal gross) {
        if (outstanding.compareTo(BigDecimal.ZERO) == 0) return SupplierBillStatus.PAID;
        if (gross != null && outstanding.compareTo(gross) >= 0) return SupplierBillStatus.MATCHED;
        return SupplierBillStatus.PARTIALLY_PAID;
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
