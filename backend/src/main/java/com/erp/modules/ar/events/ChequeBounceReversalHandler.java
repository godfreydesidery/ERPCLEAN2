package com.erp.modules.ar.events;

import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.entity.ArReceiptAllocation;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.cashbank.domain.dto.ChequeBouncedPayload;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code CHEQUE.BOUNCED} for INBOUND cheques (ADR-0041 D3 — closes the deferred D-9
 * follow-up). Posts an APPEND-ONLY reversing JournalEntry of the owning AR receipt's cash leg
 * (DR AR-control / CR Cash|Bank — the exact inverse of the receipt, produced by the GL engine's
 * {@code postReversal}, so ΣDR == ΣCR by construction). It then stamps {@code reversed_at} on the
 * receipt and restores the relieved invoice outstanding (face + base).
 *
 * <p>Never mutates or deletes the original receipt journal — the reversal is a new entry.
 *
 * <p>Idempotency + anomaly isolation mirror {@link com.erp.modules.gl.events.SaleVoidingHandler}:
 * dedup via {@link IdempotencyGuard}; a missing/already-reversed receipt is an anomaly that is
 * logged and still marked processed (no phantom reversal, no infinite retry).
 */
@Component
public class ChequeBounceReversalHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ChequeBounceReversalHandler.class);

    static final String CONSUMER = "AR.CHEQUE_BOUNCE_REVERSAL";

    private final IdempotencyGuard guard;
    private final ArReceiptRepository receipts;
    private final ArReceiptAllocationRepository allocations;
    private final ArInvoiceRepository invoices;
    private final CompanyRepository companies;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;

    public ChequeBounceReversalHandler(IdempotencyGuard guard,
                                       ArReceiptRepository receipts,
                                       ArReceiptAllocationRepository allocations,
                                       ArInvoiceRepository invoices,
                                       CompanyRepository companies,
                                       GLPostingSafeInvoker safeInvoker,
                                       ObjectMapper objectMapper) {
        this.guard        = guard;
        this.receipts     = receipts;
        this.allocations  = allocations;
        this.invoices     = invoices;
        this.companies    = companies;
        this.safeInvoker  = safeInvoker;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.CHEQUE_BOUNCED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("ChequeBounceReversalHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        ChequeBouncedPayload payload = deserialise(event.getPayload());
        Long companyId = event.getCompanyId();

        // INBOUND only — the AP handler owns OUTBOUND reversals.
        if (!"INBOUND".equals(payload.direction()) || payload.arReceiptUid() == null) {
            log.debug("ChequeBounceReversalHandler: cheque uid={} is not an INBOUND receipt-linked "
                    + "bounce — skipping (AP handles OUTBOUND).", payload.chequeUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(companyId, event.getBranchId()));
        try {
            reverseReceipt(payload, companyId, event.getUid());
        } catch (Exception ex) {
            log.warn("ChequeBounceReversalHandler: reversal failed for receipt uid={} company={} — "
                            + "anomaly recorded, marking processed. error={}",
                    payload.arReceiptUid(), companyId, ex.getMessage());
        } finally {
            if (previous == null) RequestContext.clear();
            else RequestContext.set(previous);
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    // -------------------------------------------------------------------------

    private void reverseReceipt(ChequeBouncedPayload payload, Long companyId, String eventUid) {
        ArReceipt receipt = receipts.findByCompanyIdAndUid(companyId, payload.arReceiptUid())
                .orElse(null);
        if (receipt == null) {
            log.warn("ChequeBounceReversalHandler: receipt uid={} not found in company {} — anomaly. "
                    + "event uid={}", payload.arReceiptUid(), companyId, eventUid);
            return;
        }
        if (receipt.getReversedAt() != null) {
            log.debug("ChequeBounceReversalHandler: receipt uid={} already reversed at {} — skipping",
                    receipt.getUid(), receipt.getReversedAt());
            return;
        }
        if (receipt.getGlEntryUid() == null) {
            log.warn("ChequeBounceReversalHandler: receipt uid={} has no GL entry — cannot reverse. "
                    + "event uid={}", receipt.getUid(), eventUid);
            return;
        }

        LocalDate reversalDate = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);

        // APPEND-ONLY reversal of the receipt's cash-leg journal. The engine reverses every leg of
        // the original (DR Cash / CR AR → DR AR / CR Cash), so ΣDR == ΣCR by construction.
        var reversal = safeInvoker.postReversalInNewTx(
                receipt.getGlEntryUid(),
                reversalDate,
                JournalSourceType.AR_RECEIPT,
                receipt.getUid(),
                null /* SYSTEM actor */);

        if (reversal == null) {
            // GL anomaly (closed period, etc.) — do not touch the sub-ledger; leave for replay/audit.
            log.warn("ChequeBounceReversalHandler: GL reversal returned null for receipt uid={} "
                    + "(original entry {}) — sub-ledger left intact. event uid={}",
                    receipt.getUid(), receipt.getGlEntryUid(), eventUid);
            return;
        }

        // Restore the invoice outstanding relieved by this receipt's allocations (face + base).
        // Mirrors ArReceiptServiceImpl.reallocate restore math.
        int baseScale = baseMinorUnits(companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS"));
        List<ArReceiptAllocation> allocs = allocations.findByReceiptId(receipt.getId());
        for (ArReceiptAllocation alloc : allocs) {
            invoices.findById(alloc.getArInvoiceId()).ifPresent(inv -> {
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
                invoices.save(inv);
            });
        }

        // Stamp reversal markers (append-only on the header; the journal itself is never mutated).
        receipt.setReversedAt(Instant.now());
        receipt.setUpdatedAt(Instant.now());
        receipts.save(receipt);

        log.info("ChequeBounceReversalHandler: reversed receipt uid={} (reversal entry {}) for "
                        + "bounced cheque uid={} company={}",
                receipt.getUid(), reversal.uid(), payload.chequeUid(), companyId);
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

    private ChequeBouncedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, ChequeBouncedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise ChequeBouncedPayload: " + ex.getMessage(), ex);
        }
    }
}
