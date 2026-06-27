package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.ConvertOpportunityRequest;
import com.erp.modules.crm.domain.dto.ConvertOpportunityRequest.ConvertTarget;
import com.erp.modules.crm.domain.dto.ConvertResultDto;
import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.entity.OpportunityLine;
import com.erp.modules.crm.domain.enums.OpportunityStatus;
import com.erp.modules.crm.repository.OpportunityLineRepository;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CreateQuotationRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.service.QuotationService;
import com.erp.modules.sales.service.SalesOrderService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CRM→O2C convert seam (ADR-0031 D-7).
 * Synchronous service call into QuotationService/SalesOrderService (not an outbox event).
 * Idempotency: a convert guard + uq_opportunity_converted_document unique constraint.
 * Gate: OPEN→QUOTATION allowed; SALES_ORDER requires WON (OQ-CRM-04).
 */
@Service
@Transactional
public class OpportunityConversionServiceImpl implements OpportunityConversionService {

    private final OpportunityRepository opportunities;
    private final OpportunityLineRepository lines;
    private final CompanyRepository companies;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final QuotationService quotationService;
    private final SalesOrderService salesOrderService;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public OpportunityConversionServiceImpl(OpportunityRepository opportunities,
                                            OpportunityLineRepository lines,
                                            CompanyRepository companies,
                                            ProductRepository products,
                                            UnitOfMeasureRepository units,
                                            QuotationService quotationService,
                                            SalesOrderService salesOrderService,
                                            ScopeGuard scopeGuard,
                                            AuditService audit) {
        this.opportunities = opportunities;
        this.lines = lines;
        this.companies = companies;
        this.products = products;
        this.units = units;
        this.quotationService = quotationService;
        this.salesOrderService = salesOrderService;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public ConvertResultDto convert(String opportunityUid, ConvertOpportunityRequest req) {
        Opportunity opp = Lookups.orNotFound(opportunities.findByUid(opportunityUid), "Opportunity", opportunityUid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());

        // Idempotency guard (BR-CRM-06): reject if already converted
        if (opp.getConvertedDocumentUid() != null) {
            return new ConvertResultDto(
                    opp.getUid(),
                    opp.getConvertedDocumentKind(),
                    opp.getConvertedDocumentUid(),
                    null   // document number not re-fetched (idempotent return)
            );
        }

        // Convert gate (OQ-CRM-04)
        if (req.target() == ConvertTarget.SALES_ORDER && opp.getOpportunityStatus() != OpportunityStatus.WON) {
            throw new IllegalStateException(
                    "Converting to a SALES_ORDER requires the opportunity to be WON; current: "
                            + opp.getOpportunityStatus());
        }
        if (req.target() == ConvertTarget.QUOTATION
                && opp.getOpportunityStatus() != OpportunityStatus.OPEN
                && opp.getOpportunityStatus() != OpportunityStatus.WON) {
            throw new IllegalStateException(
                    "Cannot convert a LOST opportunity; current: " + opp.getOpportunityStatus());
        }

        // Lines required for convert (OQ-CRM-03)
        List<OpportunityLine> oppLines = lines.findByOpportunityIdOrderByLineNo(opp.getId());
        if (oppLines.isEmpty()) {
            throw new IllegalStateException("Opportunity has no lines; at least one line is required for conversion.");
        }

        Company company = companies.findById(opp.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        String companyUid = company.getUid();
        // customer uid is stored on the opportunity
        String customerUid = opp.getCustomerUid();
        String agentUid = null; // agent id stored, not uid — pass null, O2C will resolve from context
        // We need the agent uid — look it up from the ID (not stored as uid on opportunity directly)
        // The agent_id is stored; for the O2C create we pass agentUid=null (optional field; agent auto-
        // resolved from user in QuotationServiceImpl). This is acceptable per ADR: agentUid is optional.

        String docUid;
        String docKind;
        String docNumber = null;

        if (req.target() == ConvertTarget.QUOTATION) {
            LocalDate quoteDate = LocalDate.now();
            LocalDate validUntil = req.validUntil() != null ? req.validUntil() : quoteDate.plusDays(30);
            CreateQuotationRequest qReq = new CreateQuotationRequest(
                    companyUid, customerUid, agentUid,
                    CurrencyCode.value(opp.getCurrency()), quoteDate, validUntil,
                    null, null, null,
                    opp.getUid()  // sourceOpportunityUid (ADR-0031 D-7)
            );
            QuotationDto quote = quotationService.create(qReq);
            docUid = quote.uid();
            docKind = "QUOTATION";
            // Add lines to the quotation
            for (OpportunityLine ol : oppLines) {
                Product product = products.findById(ol.getProductId())
                        .orElseThrow(() -> new NotFoundException("Product not found."));
                UnitOfMeasure unit = units.findById(ol.getUnitId())
                        .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
                AddQuotationLineRequest lineReq = new AddQuotationLineRequest(
                        product.getUid(), unit.getUid(),
                        ol.getEstimatedQty(),
                        ol.getEstimatedUnitPriceAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                                ? ol.getEstimatedUnitPriceAmount() : null,
                        ol.getLineDiscountAmount(), ol.getLineDiscountPercent()
                );
                quotationService.addLine(quote.uid(), lineReq);
            }
            docNumber = quote.quoteNumber();
        } else {
            // SALES_ORDER (requires WON — already gated above)
            CreateSalesOrderRequest soReq = new CreateSalesOrderRequest(
                    companyUid, customerUid, agentUid,
                    CurrencyCode.value(opp.getCurrency()), LocalDate.now(),
                    null, null, null,
                    opp.getUid()  // sourceOpportunityUid (ADR-0031 D-7)
            );
            SalesOrderDto so = salesOrderService.create(soReq);
            docUid = so.uid();
            docKind = "SALES_ORDER";
            // Add lines to the SO
            for (OpportunityLine ol : oppLines) {
                Product product = products.findById(ol.getProductId())
                        .orElseThrow(() -> new NotFoundException("Product not found."));
                UnitOfMeasure unit = units.findById(ol.getUnitId())
                        .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
                AddSalesOrderLineRequest lineReq = new AddSalesOrderLineRequest(
                        product.getUid(), unit.getUid(),
                        ol.getEstimatedQty(),
                        ol.getEstimatedUnitPriceAmount().compareTo(java.math.BigDecimal.ZERO) > 0
                                ? ol.getEstimatedUnitPriceAmount() : null,
                        ol.getLineDiscountAmount(), ol.getLineDiscountPercent()
                );
                salesOrderService.addLine(so.uid(), lineReq);
            }
            docNumber = so.orderNumber();
        }

        // Stamp the opportunity with the converted document (DB unique constraint backstop)
        opp.setConvertedDocumentKind(docKind);
        opp.setConvertedDocumentUid(docUid);
        opp.setConvertedAt(Instant.now());
        opp.setUpdatedAt(Instant.now());
        opp.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_OPPORTUNITY_CONVERT, "opportunities",
                opp.getId(), opp.getUid())
                .detail(Map.of("docKind", docKind, "docUid", docUid)));

        return new ConvertResultDto(opp.getUid(), docKind, docUid, docNumber);
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
