package com.erp.modules.sales.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One returned line of a {@link PosSaleReturn} (V117): which sold line, how much of it, and the
 * revenue/VAT given back for that quantity. Append-only. The quantity already returned per sold line
 * is derived from these rows — there is no counter on {@code sales_invoice_lines}.
 *
 * <p>{@code costAmount} stays NULL: the cost put back into stock is only known to the stock module,
 * which consumes the {@code POS.SALE.RETURNED} event; sales must not reach into stock's ledger.
 */
@Getter
@Entity
@Table(name = "pos_sale_return_lines")
public class PosSaleReturnLine extends UidEntity {

    @Column(name = "pos_sale_return_id", nullable = false, updatable = false)
    private Long posSaleReturnId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private short lineNo;

    @Column(name = "sales_invoice_line_id", nullable = false, updatable = false)
    private Long salesInvoiceLineId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** In the sold line's own unit. */
    @Column(name = "qty_returned", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal qtyReturned;

    @Column(name = "qty_returned_base", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal qtyReturnedBase;

    @Column(name = "net_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "discount_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount;

    @Column(name = "vat_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal vatAmount;

    @Column(name = "gross_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "cost_amount", precision = 19, scale = 4)
    private BigDecimal costAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected PosSaleReturnLine() {
        // JPA
    }

    public PosSaleReturnLine(Long posSaleReturnId, Long companyId, short lineNo,
                             Long salesInvoiceLineId, Long productId, BigDecimal qtyReturned,
                             BigDecimal qtyReturnedBase, BigDecimal netAmount,
                             BigDecimal discountAmount, BigDecimal vatAmount,
                             BigDecimal grossAmount, Long createdBy) {
        this.posSaleReturnId    = posSaleReturnId;
        this.companyId          = companyId;
        this.lineNo             = lineNo;
        this.salesInvoiceLineId = salesInvoiceLineId;
        this.productId          = productId;
        this.qtyReturned        = qtyReturned;
        this.qtyReturnedBase    = qtyReturnedBase;
        this.netAmount          = netAmount;
        this.discountAmount     = discountAmount;
        this.vatAmount          = vatAmount;
        this.grossAmount        = grossAmount;
        this.createdBy          = createdBy;
    }
}
