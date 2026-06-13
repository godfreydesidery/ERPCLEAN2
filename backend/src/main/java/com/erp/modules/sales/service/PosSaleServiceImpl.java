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
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
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

    public PosSaleServiceImpl(PosSessionRepository posSessionRepo,
                               SalesInvoiceRepository invoiceRepo,
                               SalesInvoiceService invoiceService,
                               ProductRepository products,
                               UnitOfMeasureRepository units,
                               CustomerRepository customers,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.posSessionRepo = posSessionRepo;
        this.invoiceRepo    = invoiceRepo;
        this.invoiceService = invoiceService;
        this.products       = products;
        this.units          = units;
        this.customers      = customers;
        this.companies      = companies;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
    }

    @Override
    public SalesInvoiceDto processSale(PosSaleRequest req) {
        // 1 — Resolve session and validate it's OPEN
        var session = posSessionRepo.findByUid(req.sessionUid())
                .orElseThrow(() -> NotFoundException.of("PosSession", req.sessionUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        if (session.getStatus() != com.erp.modules.sales.domain.enums.PosSessionStatus.OPEN) {
            throw new ConflictException("POS session " + req.sessionUid() + " is not OPEN.");
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

        // 5 — Add lines
        for (var line : req.lines()) {
            var product = products.findById(line.productId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(line.productId())));
            var unit = units.findById(line.unitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(line.unitId())));
            var lineReq = new AddInvoiceLineRequest(
                    product.getUid(), unit.getUid(),
                    line.quantity(), line.lineDiscountAmount(), null);
            invoiceService.addLine(invoiceUid, lineReq);
        }

        // 6 — Add CASH payment for the full gross amount (re-read totals after lines)
        SalesInvoice reloaded = invoiceRepo.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        BigDecimal grossTotal = reloaded.getGrossTotalAmount();
        invoiceService.addPayment(invoiceUid,
                new AddPaymentRequest(TenderType.CASH, grossTotal, req.currency(), null));

        // 7 — Finalise
        invoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest());

        // 8 — Audit POS sale
        audit.record(AuditEvent.of(AuditActions.POS_SALE_FINALISE, "sales_invoices",
                reloaded.getId(), invoiceUid)
                .detail(Map.of("sessionUid", req.sessionUid(),
                               "gross", grossTotal.toPlainString())));

        return invoiceService.getByUid(invoiceUid);
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }
}
