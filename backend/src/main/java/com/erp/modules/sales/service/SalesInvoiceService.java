package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.OverrideLinePriceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.dto.SalesInvoicePaymentDto;
import com.erp.modules.sales.domain.dto.CreateTaxRateRequest;
import com.erp.modules.sales.domain.dto.TaxRateDto;
import com.erp.modules.sales.domain.dto.UpdateInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.UpdateTaxRateRequest;
import com.erp.modules.sales.domain.dto.VatOutputSummaryDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SalesInvoiceService {

    // --- Invoice lifecycle ---
    SalesInvoiceDto create(CreateSalesInvoiceRequest req);

    SalesInvoiceDto getByUid(String uid);

    Page<SalesInvoiceDto> list(Long companyId, String q, Pageable pageable);

    /**
     * SAL-10: the invoice list with its filters applied — {@code q} matches the invoice number OR
     * the customer's name, {@code status} (DRAFT/FINALISED/VOID) and the creation-date window are
     * optional, and the result is newest first unless {@code pageable} carries a sort.
     */
    Page<SalesInvoiceDto> list(Long companyId, String q, String status,
                               java.time.LocalDate fromDate, java.time.LocalDate toDate,
                               Pageable pageable);

    /**
     * POS sales of one branch finalised at or after {@code from}, newest first — the till's
     * "Today's sales". Reversed (VOID) sales are included: a refund is part of the day, and the
     * till reprints it as REVERSED rather than letting it vanish from the list.
     */
    Page<SalesInvoiceDto> listPosSalesSince(Long companyId, Long branchId, Instant from,
                                            Pageable pageable);

    void finalise(String uid, FinaliseInvoiceRequest req);

    void voidInvoice(String uid, VoidInvoiceRequest req);

    /**
     * SAL-13 / LSF-17: discards an abandoned DRAFT invoice. A draft has no number and no postings
     * (no GL, stock or AR effect), and the schema only admits VOID with an invoice number, so the
     * draft — its lines and any not-yet-finalised tenders — is deleted outright rather than voided.
     * Quantities a delivery-billed draft reserved are released back to that delivery (SAL-07).
     * Refused for anything that is not a DRAFT, and for a till draft whose session is still open
     * (the till owns it).
     *
     * @param reason optional free text kept in the audit trail
     */
    void cancelDraft(String uid, String reason);

    /**
     * POS-specific reversal path (busy-day-simulation fix). Unlike {@link #voidInvoice}, this does
     * NOT block on the FLOW-ORDER-TO-CASH-027 direct-payment guard — every POS sale is paid at the
     * till, so that guard would make {@code POS.SALE.VOID} permanently unsatisfiable. The
     * AR-allocation guard still applies. Callers must restrict use to a POS-origin invoice on an
     * OPEN session (see {@code PosSaleServiceImpl#reverseSale}) — this method does not re-check
     * origin/session state itself.
     */
    void voidPosInvoice(String uid, VoidInvoiceRequest req);

    // --- Lines ---
    SalesInvoiceLineDto addLine(String invoiceUid, AddInvoiceLineRequest req);

    SalesInvoiceLineDto updateLine(String invoiceUid, String lineUid, UpdateInvoiceLineRequest req);

    void removeLine(String invoiceUid, String lineUid);

    List<SalesInvoiceLineDto> listLines(String invoiceUid);

    SalesInvoiceLineDto overrideLinePrice(String invoiceUid, String lineUid, OverrideLinePriceRequest req);

    // --- Payments ---
    SalesInvoicePaymentDto addPayment(String invoiceUid, AddPaymentRequest req);

    void removePayment(String invoiceUid, String paymentUid);

    List<SalesInvoicePaymentDto> listPayments(String invoiceUid);

    // --- GL posting read (ADR-0013 D-12) ---
    /**
     * Returns the monetary totals a GL posting handler needs for a finalised invoice.
     * Company-scoped so no cross-tenant read is possible. GL imports this DTO, never the entity.
     */
    Optional<InvoicePostingTotalsDto> findPostingTotalsByUidAndCompany(String invoiceUid, Long companyId);

    /**
     * SAL-06 / ACC-05: the counter tenders on an invoice (amount net of change, and the cash/bank
     * account each landed in), so the GL sale posting can debit each tender's own account and AR
     * only for the outstanding. Company-scoped; empty when the invoice is not found.
     */
    List<com.erp.modules.sales.domain.dto.InvoicePostingTenderDto> findPostingTendersByUidAndCompany(
            String invoiceUid, Long companyId);

    // --- VAT computation read (ADR-0017 D-6) ---
    /**
     * Sums output VAT from FINALISED invoices whose finalised_at date falls in [start, end],
     * grouped by tax band. Used by the tax module's VatReturnComputationReader; company-scoped.
     * Tax module imports this DTO, never a Sales entity (NFR-VAT-06 / D-10).
     */
    VatOutputSummaryDto findVatSummaryForPeriod(Long companyId, LocalDate start, LocalDate end);

    // --- Tax rates ---
    TaxRateDto getTaxRateByUid(String uid);

    List<TaxRateDto> listTaxRates(Long companyId);

    TaxRateDto createTaxRate(CreateTaxRateRequest req);

    TaxRateDto updateTaxRate(String uid, UpdateTaxRateRequest req);
}
