package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.DocumentType;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Sales invoice header (ADR-0008 D-2, FR-SALES-01).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version optimistic lock).
 * company_id and branch_id are scalar Longs — no cross-module @ManyToOne (ADR-0008 D-1).
 * customer_id and agent_id are scalar Longs — DB FK only, no JPA association.
 * Totals are plain BigDecimal sharing the single header currency column (ADR-0008 D-2).
 *
 * <p>Snapshot columns on the header (tax_summary) are set at finalise and never re-read.
 * Lifecycle: DRAFT → FINALISED → VOID. Immutable after FINALISED (BR-SALES-08).
 */
@Getter
@Entity
@Table(name = "sales_invoices")
public class SalesInvoice extends UidEntity {

    /** FK → companies.id; tenant scope; never updated (BR-SALES-01). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → branches.id; the branch the sale was raised at; never updated (BR-SALES-01). */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType = DocumentType.INVOICE;

    /** Assigned at finalise (INV-####); NULL while DRAFT (ADR-0008 D-7). */
    @Column(name = "invoice_number", length = 30)
    @Setter
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    /** FK → customers.id; scalar, same company (service-enforced). */
    @Column(name = "customer_id", nullable = false)
    @Setter
    private Long customerId;

    /** FK → agents.id; mandatory (BR-SALES-06). */
    @Column(name = "agent_id", nullable = false)
    @Setter
    private Long agentId;

    /** Document currency (ISO 4217). All monetary columns share this currency (BR-SALES-04). */
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    /** Customer's own purchase-order reference (P2-M3). Nullable. */
    @Column(name = "customer_po_number", length = 60)
    @Setter
    private String customerPoNumber;

    /** Document-level discount amount (optional). Apportioned across lines before VAT. */
    @Column(name = "doc_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal docDiscountAmount;

    /** Document-level discount percent (alternative entry; resolved to amount at compute time). */
    @Column(name = "doc_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal docDiscountPercent;

    /** Computed roll-up: sum of line nets after doc discount (ADR-0008 D-4). DEFAULT 0. */
    @Column(name = "net_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netTotalAmount = BigDecimal.ZERO;

    /** Computed roll-up: sum of line VAT amounts (ADR-0008 D-4). DEFAULT 0. */
    @Column(name = "vat_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal vatTotalAmount = BigDecimal.ZERO;

    /** Computed roll-up: netTotal + vatTotal (ADR-0008 D-4). DEFAULT 0. */
    @Column(name = "gross_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossTotalAmount = BigDecimal.ZERO;

    /** Per-rate-band VAT analysis JSONB — set at finalise (FR-SALES-11/13). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_summary", columnDefinition = "jsonb")
    @Setter
    private String taxSummary;

    @Column(name = "finalised_at")
    @Setter
    private Instant finalisedAt;

    /** FK → app_users.id; who finalised. */
    @Column(name = "finalised_by")
    @Setter
    private Long finalisedBy;

    @Column(name = "voided_at")
    @Setter
    private Instant voidedAt;

    /** FK → app_users.id; who voided. */
    @Column(name = "voided_by")
    @Setter
    private Long voidedBy;

    @Column(name = "void_reason", length = 255)
    @Setter
    private String voidReason;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    // -------------------------------------------------------------------------
    // ADR-0036 D-4 — FX base triple (V78); stamped once at finalise, immutable thereafter.
    // base_gross_total_amount is the gross total converted to the company's base currency.
    // Stored on the document, NOT on journal_lines (the base-only-ledger stance, D-3).
    // -------------------------------------------------------------------------

    /**
     * Effective exchange rate at finalise (units of base per 1 foreign unit, scale 8). DEFAULT 1.
     * Written once at finalise; service enforces immutability (no setter called after FINALISED).
     * updatable=true because this column is NULL/DEFAULT until the DRAFT→FINALISED UPDATE (D-4).
     */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /**
     * Gross total converted to company base currency at {@code fxRate}. NULL until finalised.
     * Written once at finalise by the FX stamp (ADR-0036 D-4); service enforces immutability.
     */
    @Column(name = "base_gross_total_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseGrossTotalAmount;

    /**
     * Timestamp when the rate was stamped (matches the rate effective date, immutable after write).
     * Written once at finalise; updatable=true required so the DRAFT→FINALISED UPDATE persists it.
     */
    @Column(name = "rate_at")
    @Setter
    private Instant rateAt;

    // -------------------------------------------------------------------------
    // ADR-0021 D-8 — invoice origin seam fields (additive, V18)
    // -------------------------------------------------------------------------

    /**
     * DIRECT = walk-in invoice (issues stock on finalise).
     * SALES_ORDER = invoice raised from a delivery (revenue-only — delivery already issued).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    @Setter
    private DocumentOrigin origin = DocumentOrigin.DIRECT;

    /** The SO this invoice bills (when origin = SALES_ORDER). */
    @Column(name = "source_order_uid", length = 26)
    @Setter
    private String sourceOrderUid;

    /** The delivery this invoice bills (partial-invoicing trace, D-10). */
    @Column(name = "source_delivery_uid", length = 26)
    @Setter
    private String sourceDeliveryUid;

    /**
     * FK → routes.id; nullable (BR-ROUTE-05 — a sale is never blocked when the route is blank).
     * Defaulted at create from the selling agent's primary route (FR-ROUTE-13, ADR-0012 D-6).
     * Captured-not-validated (BR-ROUTE-09, §10 accepted risk).
     */
    @Column(name = "route_id")
    @Setter
    private Long routeId;

    // --- cost-centre (ADR-0025 D-6, V28) — header-level dimension default ---
    // Nullable. When set, SalesPostingHandler stamps the revenue/COGS LineDrafts with these ids.
    /** FK → dimension_values(id); Cost Centre dimension default (nullable). */
    @Column(name = "cost_centre_value_id")
    @Setter
    private Long costCentreValueId;

    /** FK → dimension_values(id); Department dimension default (nullable). */
    @Column(name = "department_value_id")
    @Setter
    private Long departmentValueId;

    // --- projects (ADR-0033 D-3, V66) — optional project dimension tag ---
    /** FK → projects(id); nullable — analysis tag for project-billed sales. */
    @Column(name = "project_id")
    @Setter
    private Long projectId;

    /** FK → project_tasks(id); nullable. */
    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

    // --- sales-depth (ADR-0029 D-5, V43) — POS session tag ---
    /**
     * FK → pos_sessions.id; set when origin=POS; null for all other origins.
     * The till-closure reconciliation reads all FINALISED invoices with this session id.
     */
    @Column(name = "pos_session_id")
    @Setter
    private Long posSessionId;

    // --- ADR-0040 D-3 — ship-to / bill-to address snapshot fields ---
    /** FK → customer_addresses.id; nullable — delivery address used for this invoice. */
    @Column(name = "ship_to_address_id")
    @Setter
    private Long shipToAddressId;

    /** FK → customer_addresses.id; nullable — billing address used for this invoice. */
    @Column(name = "bill_to_address_id")
    @Setter
    private Long billToAddressId;

    /** Free-text shipping address override / snapshot (at most 500 chars). */
    @Column(name = "ship_to_address_text", length = 500)
    @Setter
    private String shipToAddressText;

    /** Free-text billing address override / snapshot (at most 500 chars). */
    @Column(name = "bill_to_address_text", length = 500)
    @Setter
    private String billToAddressText;

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

    protected SalesInvoice() {
        // JPA
    }

    public SalesInvoice(Long companyId, Long branchId, Long customerId, Long agentId,
                        String currency, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.customerId = customerId;
        this.agentId = agentId;
        this.currency = CurrencyCode.of(currency);
        this.createdBy = createdBy;
    }
}
