package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosSaleReturnDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnsDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import java.util.List;

/**
 * POS "refund by item" — a partial return of chosen lines/quantities from a finalised POS sale
 * (ADR-0072; the deferral recorded in ADR-0042 / known-limitations §5 is closed).
 *
 * <p>The refund is always CASH from the till drawer. The original invoice stays {@code FINALISED}
 * and is never edited; the return is its own document. Once a sale has any item return, the
 * whole-sale reverse and the back-office void are refused for it.
 */
public interface PosSaleReturnService {

    /**
     * Returns the chosen items and refunds them in cash from the till, atomically: the return and
     * its lines, the REFUND payout (so expected cash drops), the {@code POS.SALE.RETURNED} outbox
     * event (stock, GL and cash book) and the audit row are written in one transaction.
     *
     * <p>Authorisation is the sale reversal's, rule for rule: a caller may return only items of
     * sales on their OWN till session unless they hold {@code SALES.INVOICE.VOID}, and a caller who
     * does not hold it needs {@code authorisedByUid} from a manager step-up that is re-verified here.
     * Refusals are audited independently of the rolled-back transaction.
     *
     * @param idempotencyKey the till's {@code Idempotency-Key}; the same key returns the original
     *                       return instead of refunding twice. May be null (no dedup requested).
     * @throws com.erp.platform.common.api.ConflictException when the sale cannot take a return
     *         (reversed, session closed, nothing left to return, AR allocations, …)
     */
    PosSaleReturnDto returnItems(String invoiceUid, String idempotencyKey,
                                 PosSaleReturnRequest request);

    /** The returns made against a sale plus, per sold line, sold / returned / remaining quantity. */
    PosSaleReturnsDto listForSale(String invoiceUid);

    /** One return, for a reprint of its refund slip. */
    PosSaleReturnDto getByUid(String returnUid);

    /** The invoice with {@code hasReturns} / {@code returnedGross} filled in. */
    SalesInvoiceDto annotate(SalesInvoiceDto invoice);

    /** As {@link #annotate(SalesInvoiceDto)} for a page of invoices, in one query. */
    List<SalesInvoiceDto> annotate(List<SalesInvoiceDto> invoices);

    /** The invoice's lines with {@code qtyReturned} filled in. */
    List<SalesInvoiceLineDto> annotateLines(List<SalesInvoiceLineDto> lines);
}
