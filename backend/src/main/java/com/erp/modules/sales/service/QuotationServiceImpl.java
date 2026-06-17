package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.CreateQuotationRequest;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.QuotationLine;
import com.erp.modules.sales.domain.enums.QuotationStatus;
import com.erp.modules.sales.repository.QuotationLineRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class QuotationServiceImpl implements QuotationService {

    private final QuotationRepository      quotations;
    private final QuotationLineRepository  quotationLines;
    private final CustomerRepository       customers;
    private final AgentRepository          agents;
    private final CompanyRepository        companies;
    private final ProductRepository        products;
    private final UnitOfMeasureRepository  units;
    private final ProductPriceRepository   prices;
    private final ProductBulkPackRepository bulkPacks;
    private final TaxRateRepository        taxRates;
    private final SalesOrderService        salesOrderService;
    private final OrderToCashNumberGenerator numberGen;
    private final SalesOrderTotalsCalculator totalsCalc;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public QuotationServiceImpl(QuotationRepository quotations,
                                QuotationLineRepository quotationLines,
                                CustomerRepository customers,
                                AgentRepository agents,
                                CompanyRepository companies,
                                ProductRepository products,
                                UnitOfMeasureRepository units,
                                ProductPriceRepository prices,
                                ProductBulkPackRepository bulkPacks,
                                TaxRateRepository taxRates,
                                SalesOrderService salesOrderService,
                                OrderToCashNumberGenerator numberGen,
                                SalesOrderTotalsCalculator totalsCalc,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.quotations      = quotations;
        this.quotationLines  = quotationLines;
        this.customers       = customers;
        this.agents          = agents;
        this.companies       = companies;
        this.products        = products;
        this.units           = units;
        this.prices          = prices;
        this.bulkPacks       = bulkPacks;
        this.taxRates        = taxRates;
        this.salesOrderService = salesOrderService;
        this.numberGen       = numberGen;
        this.totalsCalc      = totalsCalc;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public QuotationDto create(CreateQuotationRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);

        Long branchId = requireBranchId(ctx);
        Customer customer = resolveCustomer(companyId, req.customerUid());
        Long agentId = resolveAgentId(companyId, req.agentUid(), ctx);

        DiscountValidator.validateDocDiscount(req.docDiscountAmount(), req.docDiscountPercent());

        Quotation q = new Quotation(companyId, branchId, customer.getId(), agentId,
                req.currency(), req.quoteDate(), req.validUntil(), actorId());
        q.setDocDiscountAmount(req.docDiscountAmount());
        q.setDocDiscountPercent(req.docDiscountPercent());
        q.setNotes(req.notes());
        // ADR-0031 D-7 back-link: set when called from CRM OpportunityConversionService
        q.setSourceOpportunityUid(req.sourceOpportunityUid());

        Quotation saved = quotations.save(q);
        audit.record(AuditEvent.of(AuditActions.QUOTATION_CREATE, "quotations",
                saved.getId(), saved.getUid())
                .detail(Map.of("customerUid", req.customerUid())));
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationDto getByUid(String uid) {
        Quotation q = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        return toDto(q);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuotationDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return quotations.findByCompanyId(companyId, pageable)
                .map(this::toDtoNoLines);
    }

    @Override
    public QuotationLineDto addLine(String quotationUid, AddQuotationLineRequest req) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        assertDraft(q);

        Product product = resolveProduct(q.getCompanyId(), req.productUid());
        assertSellable(product);
        UnitOfMeasure unit = resolveUnit(q.getCompanyId(), req.unitUid());
        BigDecimal listPrice = resolveListPrice(product, q.getCompanyId());
        BigDecimal appliedPrice = req.unitPriceOverride() != null ? req.unitPriceOverride() : listPrice;
        BigDecimal vatRate = resolveVatRate(q.getCompanyId(), product);
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());
        short lineNo = (short) (quotationLines.findMaxLineNo(q.getId()) + 1);

        DiscountValidator.validateLineDiscount(req.lineDiscountAmount(), req.lineDiscountPercent());

        QuotationLine line = new QuotationLine(q.getId(), q.getCompanyId(), q.getBranchId(), lineNo,
                product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(),
                req.quantity(), qtyInBase,
                listPrice, appliedPrice,
                product.getVatStatus(), vatRate,
                q.getCurrency().value(), actorId());
        line.setLineDiscountAmount(req.lineDiscountAmount());
        line.setLineDiscountPercent(req.lineDiscountPercent());

        QuotationLine saved = quotationLines.save(line);
        recomputeTotals(q);
        return QuotationLineDto.from(saved);
    }

    @Override
    public void removeLine(String quotationUid, String lineUid) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        assertDraft(q);
        QuotationLine line = quotationLines.findByUidAndQuotationId(lineUid, q.getId())
                .orElseThrow(() -> new NotFoundException("QuotationLine not found: " + lineUid));
        quotationLines.delete(line);
        recomputeTotals(q);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuotationLineDto> listLines(String quotationUid) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        return quotationLines.findByQuotationIdOrderByLineNo(q.getId())
                .stream().map(QuotationLineDto::from).toList();
    }

    @Override
    public QuotationDto send(String quotationUid) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT quotations can be sent; current: " + q.getStatus());
        }
        // Check validity date not in the past
        if (q.getValidUntil().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Valid-until date is in the past; cannot send.");
        }

        String number = numberGen.nextQuote(q.getCompanyId());
        q.setQuoteNumber(number);
        q.setStatus(QuotationStatus.SENT);
        q.setSentAt(Instant.now());
        q.setUpdatedAt(Instant.now());
        q.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.QUOTATION_SEND, "quotations", q.getId(), q.getUid())
                .detail(Map.of("quoteNumber", number)));
        return toDto(q);
    }

    @Override
    public SalesOrderDto accept(String quotationUid) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new IllegalStateException("Only SENT quotations can be accepted; current: " + q.getStatus());
        }
        // Expiry guard
        if (q.getValidUntil().isBefore(LocalDate.now())) {
            q.setStatus(QuotationStatus.EXPIRED);
            q.setExpiredAt(Instant.now());
            q.setUpdatedAt(Instant.now());
            q.setUpdatedBy(actorId());
            throw new IllegalStateException("Quotation " + q.getQuoteNumber() + " has expired (valid until "
                    + q.getValidUntil() + ") and cannot be accepted (BR-SO-01).");
        }

        SalesOrderDto so = salesOrderService.createFromQuotation(quotationUid);

        q.setStatus(QuotationStatus.ACCEPTED);
        q.setAcceptedAt(Instant.now());
        q.setConvertedOrderUid(so.uid());
        q.setUpdatedAt(Instant.now());
        q.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.QUOTATION_ACCEPT, "quotations", q.getId(), q.getUid())
                .detail(Map.of("salesOrderUid", so.uid())));
        return so;
    }

    @Override
    public void reject(String quotationUid) {
        Quotation q = require(quotationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        if (q.getStatus() != QuotationStatus.SENT) {
            throw new IllegalStateException("Only SENT quotations can be rejected; current: " + q.getStatus());
        }
        q.setStatus(QuotationStatus.REJECTED);
        q.setRejectedAt(Instant.now());
        q.setUpdatedAt(Instant.now());
        q.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.QUOTATION_REJECT, "quotations", q.getId(), q.getUid())
                .detail(Map.of()));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Quotation require(String uid) {
        return Lookups.orNotFound(quotations.findByUid(uid), "Quotation", uid);
    }

    private void assertDraft(Quotation q) {
        if (q.getStatus() != QuotationStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot modify a non-DRAFT quotation; current: " + q.getStatus());
        }
    }

    private Long resolveCompanyId(String uid) {
        return companies.findByUid(uid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + uid));
    }

    private Customer resolveCustomer(Long companyId, String uid) {
        return customers.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + uid));
    }

    private Long resolveAgentId(Long companyId, String agentUid, RequestContext.Principal ctx) {
        if (agentUid != null && !agentUid.isBlank()) {
            Agent a = agents.findByCompanyIdAndUid(companyId, agentUid)
                    .orElseThrow(() -> new NotFoundException("Agent not found: " + agentUid));
            return a.getId();
        }
        if (ctx != null && ctx.userId() != null) {
            Optional<Long> auto = agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
            if (auto.isPresent()) return auto.get();
        }
        return null; // agent is optional on a quote
    }

    private Product resolveProduct(Long companyId, String uid) {
        return products.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Product not found: " + uid));
    }

    private void assertSellable(Product product) {
        if (!product.isSellable() || product.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException("Product not sellable: " + product.getUid());
        }
    }

    private UnitOfMeasure resolveUnit(Long companyId, String uid) {
        return units.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + uid));
    }

    private BigDecimal resolveListPrice(Product product, Long companyId) {
        return prices.findByProductId(product.getId()).stream()
                .filter(p -> companyId.equals(p.getCompanyId()))
                .findFirst()
                .map(ProductPrice::getPrice)
                .filter(m -> m != null && m.getAmount() != null)
                .map(m -> m.getAmount())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product has no price for this company: " + product.getUid()));
    }

    private BigDecimal resolveVatRate(Long companyId, Product product) {
        return taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                .map(r -> r.getRate())
                .orElseThrow(() -> new IllegalStateException(
                        "VAT rate not configured for " + product.getVatStatus()));
    }

    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        return bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unit.getId()))
                .findFirst()
                .map(bp -> qty.multiply(bp.getFactorToBase()))
                .orElse(qty);
    }

    private void recomputeTotals(Quotation q) {
        List<QuotationLine> lines = quotationLines.findByQuotationIdOrderByLineNo(q.getId());
        totalsCalc.recompute(q, lines);
        q.setUpdatedAt(Instant.now());
        q.setUpdatedBy(actorId());
    }

    private QuotationDto toDto(Quotation q) {
        List<QuotationLineDto> lines = quotationLines.findByQuotationIdOrderByLineNo(q.getId())
                .stream().map(QuotationLineDto::from).toList();
        return QuotationDto.from(q, lines);
    }

    private QuotationDto toDtoNoLines(Quotation q) {
        return QuotationDto.from(q, List.of());
    }

    private Long requireBranchId(RequestContext.Principal ctx) {
        if (ctx == null || ctx.branchId() == null) {
            throw new IllegalStateException("No active branch in context.");
        }
        return ctx.branchId();
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
