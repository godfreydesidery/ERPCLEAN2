package com.erp.modules.sales.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS quick-sale orchestrator (ADR-0029 D-5, FR-SD-15).
 *
 * <p>Delegates invoice creation / line building / payment / finalise to
 * {@link SalesInvoiceService} (same module), then sets the POS-specific fields
 * (origin=POS, posSessionId) directly via the repository before finalise.
 */
@Service
@Transactional
public class PosSaleServiceImpl implements PosSaleService {

    private final PosSessionRepository  posSessionRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final SalesInvoiceService    invoiceService;
    private final ProductRepository      products;
    private final UnitOfMeasureRepository units;
    private final CustomerRepository     customers;
    private final CompanyRepository      companies;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;
    private final PosSaleIdempotencyRepository idempotency;
    /** ADR-0044 D-3a: runtime permission check for POS.SALE.AGE_OVERRIDE. */
    private final PermissionResolver     permissionResolver;

    public PosSaleServiceImpl(PosSessionRepository posSessionRepo,
                               SalesInvoiceRepository invoiceRepo,
                               SalesInvoiceService invoiceService,
                               ProductRepository products,
                               UnitOfMeasureRepository units,
                               CustomerRepository customers,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard,
                               AuditService audit,
                               PosSaleIdempotencyRepository idempotency,
                               PermissionResolver permissionResolver) {
        this.posSessionRepo    = posSessionRepo;
        this.invoiceRepo       = invoiceRepo;
        this.invoiceService    = invoiceService;
        this.products          = products;
        this.units             = units;
        this.customers         = customers;
        this.companies         = companies;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
        this.idempotency       = idempotency;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public SalesInvoiceDto processSale(String idempotencyKey, PosSaleRequest req) {
        boolean idem = idempotencyKey != null && !idempotencyKey.isBlank();

        // 1 — Resolve session and validate it's OPEN
        var session = posSessionRepo.findByUid(req.sessionUid())
                .orElseThrow(() -> NotFoundException.of("PosSession", req.sessionUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        if (session.getStatus() != com.erp.modules.sales.domain.enums.PosSessionStatus.OPEN) {
            throw new ConflictException("This POS session is not OPEN.");
        }

        // 1b — Idempotency reserve-before-process (ADR-0042 D-1): claim the key in THIS transaction
        // BEFORE creating the invoice, so a duplicate retry returns the original sale rather than
        // double-posting. ON CONFLICT DO NOTHING returns 0 on a taken key (blocking first on an
        // in-flight winner), in which case we return the winner's already-created invoice.
        Long companyId = session.getCompanyId();
        if (idem) {
            int reserved = idempotency.tryReserve(companyId, idempotencyKey);
            if (reserved == 0) {
                var marker = idempotency.findByCompanyIdAndIdemKey(companyId, idempotencyKey)
                        .orElseThrow(() -> new ConflictException(
                                "Idempotency-Key already used but its result is unavailable; retry."));
                if (marker.getInvoiceUid() == null) {
                    throw new ConflictException(
                            "A POS sale with this Idempotency-Key is still in progress; retry shortly.");
                }
                return invoiceService.getByUid(marker.getInvoiceUid());
            }
        }

        // 2 — Resolve customer UID (PosSaleRequest carries customerId)
        var customer = customers.findById(req.customerId())
                .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(req.customerId())));
        // Resolve agent — PosSaleRequest carries agentId; invoiceService needs agentUid
        // Use the company uid for the create call
        var company = companies.findById(session.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(session.getCompanyId())));

        // 3 — Create DRAFT invoice via service (handles number allocation + audit)
        var createReq = new CreateSalesInvoiceRequest(
                company.getUid(), customer.getUid(), null, req.currency(), req.notes(), null);
        var draftDto = invoiceService.create(createReq);
        String invoiceUid = draftDto.uid();

        // 4 — Stamp origin=POS and posSessionId on the entity (POS-specific fields)
        SalesInvoice invoice = invoiceRepo.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        invoice.setOrigin(DocumentOrigin.POS);
        invoice.setPosSessionId(session.getId());
        invoice.setUpdatedAt(Instant.now());
        invoice.setUpdatedBy(actorId());
        // flush so finalise sees origin=POS
        invoiceRepo.saveAndFlush(invoice);

        // 5 — Age-restriction gate (ADR-0044 D-3a, BR-11).
        // Resolve once: verified flag from request; override from caller's permission set.
        boolean ageVerified = Boolean.TRUE.equals(req.ageVerified());
        boolean ageOverride = permissionResolver.hasPermission(
                RequestContext.get(), "POS.SALE.AGE_OVERRIDE", System.currentTimeMillis());

        // 5 — Add lines (resolve products first to run the age gate before any invoice mutation)
        for (var line : req.lines()) {
            var product = products.findById(line.productId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(line.productId())));
            if (product.getRestrictedKind().isRestricted() && !ageVerified && !ageOverride) {
                throw new ConflictException(
                        "Age-restricted item '" + product.getName() + "' requires age verification"
                        + " — resend with ageVerified=true, or the cashier needs POS.SALE.AGE_OVERRIDE.");
            }
            var unit = units.findById(line.unitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(line.unitId())));
            var lineReq = new AddInvoiceLineRequest(
                    product.getUid(), unit.getUid(),
                    line.quantity(), line.lineDiscountAmount(), null);
            invoiceService.addLine(invoiceUid, lineReq);
        }

        // 6 — Settle the sale (re-read totals after lines). Multi-tender when provided (ADR-0042 D-3),
        // otherwise a single exact CASH payment — the original behaviour. The payment layer already
        // supports every TenderType + instrument refs (ADR-0041 D3) and the over-tender/change rule
        // (BR-SALES-07, CASH only); we just need the tenders to cover the gross.
        SalesInvoice reloaded = invoiceRepo.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        BigDecimal grossTotal = reloaded.getGrossTotalAmount();
        var tenders = req.tenders();
        if (tenders != null && !tenders.isEmpty()) {
            BigDecimal tendered = BigDecimal.ZERO;
            for (var t : tenders) {
                invoiceService.addPayment(invoiceUid, new AddPaymentRequest(
                        t.tenderType(), t.amount(), req.currency(), t.reference(),
                        t.cashBankAccountId(), t.chequeId(), t.mobileMoneyRef(), t.cardRef()));
                tendered = tendered.add(t.amount());
            }
            if (tendered.compareTo(grossTotal) < 0) {
                throw new ConflictException("Tenders (" + tendered.toPlainString()
                        + ") do not cover the gross total (" + grossTotal.toPlainString() + ").");
            }
        } else {
            invoiceService.addPayment(invoiceUid,
                    new AddPaymentRequest(TenderType.CASH, grossTotal, req.currency(), null));
        }

        // 7 — Finalise
        invoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        // 7b — Stamp the created invoice onto the idempotency marker (same TX as the sale, so a
        // concurrent duplicate sees it the instant this transaction commits — ADR-0042 D-1).
        if (idem) {
            idempotency.stampInvoiceUid(companyId, idempotencyKey, invoiceUid);
        }

        // 8 — Audit POS sale (includes age-gate decision for ADR-0044 D-3a traceability)
        audit.record(AuditEvent.of(AuditActions.POS_SALE_FINALISE, "sales_invoices",
                reloaded.getId(), invoiceUid)
                .detail(Map.of("sessionUid",  req.sessionUid(),
                               "gross",       grossTotal.toPlainString(),
                               "ageVerified", String.valueOf(ageVerified),
                               "ageOverride", String.valueOf(ageOverride))));

        return invoiceService.getByUid(invoiceUid);
    }

    @Override
    public void reverseSale(String invoiceUid, String reason) {
        SalesInvoice inv = invoiceRepo.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());

        // Must be a POS sale (ADR-0042 D-2) — non-POS invoices use the standard back-office void.
        if (inv.getOrigin() != DocumentOrigin.POS || inv.getPosSessionId() == null) {
            throw new ConflictException(
                    "Invoice " + invoiceUid + " is not a POS sale; use the standard invoice void.");
        }

        // Drawer rule (ADR-0042 D-2): the originating session must be OPEN so the till absorbs the
        // cash refund. The void already reverses the GL cash leg (cash sale -> SALES_REVERSAL credits
        // Cash) and stock; a settled/closed session is a back-office void, not a till reversal.
        var session = posSessionRepo.findById(inv.getPosSessionId())
                .orElseThrow(() -> NotFoundException.of(
                        "PosSession", String.valueOf(inv.getPosSessionId())));
        if (session.getStatus() != com.erp.modules.sales.domain.enums.PosSessionStatus.OPEN) {
            throw new ConflictException(
                    "POS session is not OPEN; reverse a settled session's sale via back-office void.");
        }

        invoiceService.voidInvoice(invoiceUid, new VoidInvoiceRequest(reason));

        audit.record(AuditEvent.of(AuditActions.POS_SALE_REVERSE, "sales_invoices",
                        inv.getId(), invoiceUid)
                .detail(Map.of("sessionUid", session.getUid(),
                               "reason", reason == null ? "" : reason)));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }
}
