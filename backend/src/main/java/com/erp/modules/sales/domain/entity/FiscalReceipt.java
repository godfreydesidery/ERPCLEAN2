package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.FiscalReceiptStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One EFD/fiscal receipt per FINALISED sales invoice (ADR-0049). Created and retried in place by
 * {@code FiscalReceiptServiceImpl.issue} — never written to by {@code SalesInvoiceServiceImpl}, which
 * stays completely decoupled from fiscalisation.
 *
 * <p>{@code companyId}/{@code branchId}/{@code salesInvoiceId} are scalar Longs, copied from the
 * invoice at row creation (no cross-module/-aggregate {@code @ManyToOne}, mirrors {@link SalesInvoice}).
 * {@code UNIQUE(sales_invoice_id)} (DB) enforces one receipt per invoice; issue-or-retry is a single
 * idempotent operation over this one row (see the service).
 */
@Getter
@Entity
@Table(name = "fiscal_receipts")
public class FiscalReceipt extends UidEntity {

    /** FK → companies.id; tenant scope; copied from the invoice at construction; never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → branches.id; copied from the invoice at construction; never updated. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK → sales_invoices.id; the invoice this receipt belongs to. One row per invoice (DB unique). */
    @Column(name = "sales_invoice_id", nullable = false, updatable = false)
    private Long salesInvoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FiscalReceiptStatus status = FiscalReceiptStatus.PENDING;

    /** Which provider produced the current state of this row (NOT_CONFIGURED / SIMULATED / TRA_VFD). */
    @Column(name = "provider_code", length = 30)
    private String providerCode;

    /** Device/verification receipt number — populated only when ISSUED. */
    @Column(name = "fiscal_number", length = 60)
    private String fiscalNumber;

    /** TRA verification / QR URL — populated only when ISSUED. */
    @Column(name = "verification_url", length = 500)
    private String verificationUrl;

    /** Device signature — populated only when ISSUED. */
    @Column(name = "signature", length = 512)
    private String signature;

    /** Issuing device serial — populated only when ISSUED. */
    @Column(name = "device_serial", length = 60)
    private String deviceSerial;

    /** Device-reported issue time — populated only when ISSUED. */
    @Column(name = "issued_at")
    private Instant issuedAt;

    /** Retry diagnostics: incremented on every issue/retry attempt. */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /** Last failure/not-configured reason. Friendly, bounded, non-leaky (error-message hygiene). */
    @Column(name = "error_detail", length = 500)
    private String errorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected FiscalReceipt() {
        // JPA
    }

    public FiscalReceipt(Long companyId, Long branchId, Long salesInvoiceId, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.salesInvoiceId = salesInvoiceId;
        this.createdBy = createdBy;
    }

    // -------------------------------------------------------------------------
    // State transitions (ADR-0049 §4) — each stamps attemptCount/lastAttemptAt/providerCode and
    // is the ONLY way this entity's fiscal fields change after construction.
    // -------------------------------------------------------------------------

    /** Terminal success. Not callable again in practice — the service short-circuits on ISSUED. */
    public void recordIssued(String providerCode, String fiscalNumber, String verificationUrl,
                              String signature, String deviceSerial, Instant issuedAt,
                              Instant now, Long actorId) {
        beginAttempt(providerCode, now, actorId);
        this.status = FiscalReceiptStatus.ISSUED;
        this.fiscalNumber = fiscalNumber;
        this.verificationUrl = verificationUrl;
        this.signature = signature;
        this.deviceSerial = deviceSerial;
        this.issuedAt = issuedAt;
        this.errorDetail = null;
    }

    /** A provider attempted and errored. Retryable. */
    public void recordFailed(String providerCode, String errorDetail, Instant now, Long actorId) {
        beginAttempt(providerCode, now, actorId);
        this.status = FiscalReceiptStatus.FAILED;
        this.errorDetail = errorDetail;
    }

    /** The honest default ran; nothing fabricated. Retryable. */
    public void recordNotConfigured(String providerCode, String errorDetail, Instant now, Long actorId) {
        beginAttempt(providerCode, now, actorId);
        this.status = FiscalReceiptStatus.NOT_CONFIGURED;
        this.errorDetail = errorDetail;
    }

    private void beginAttempt(String providerCode, Instant now, Long actorId) {
        this.attemptCount = this.attemptCount + 1;
        this.lastAttemptAt = now;
        this.providerCode = providerCode;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }
}
