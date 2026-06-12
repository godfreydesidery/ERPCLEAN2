package com.erp.modules.purchases.service;

import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.purchases.domain.dto.CaptureSupplierQuoteRequest;
import com.erp.modules.purchases.domain.dto.SupplierQuoteDto;
import com.erp.modules.purchases.domain.dto.SupplierQuoteLineDto;
import com.erp.modules.purchases.domain.entity.Rfq;
import com.erp.modules.purchases.domain.entity.RfqLine;
import com.erp.modules.purchases.domain.entity.SupplierQuote;
import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import com.erp.modules.purchases.repository.RfqLineRepository;
import com.erp.modules.purchases.repository.RfqRepository;
import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import com.erp.modules.purchases.repository.SupplierQuoteRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SupplierQuoteServiceImpl implements SupplierQuoteService {

    private final SupplierQuoteRepository     quotes;
    private final SupplierQuoteLineRepository quoteLines;
    private final RfqRepository               rfqs;
    private final RfqLineRepository           rfqLines;
    private final SupplierRepository          suppliers;
    private final PurchaseNumberGenerator     numberGen;
    private final ScopeGuard                  scopeGuard;
    private final AuditService                audit;

    public SupplierQuoteServiceImpl(SupplierQuoteRepository quotes,
                                     SupplierQuoteLineRepository quoteLines,
                                     RfqRepository rfqs,
                                     RfqLineRepository rfqLines,
                                     SupplierRepository suppliers,
                                     PurchaseNumberGenerator numberGen,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.quotes     = quotes;
        this.quoteLines = quoteLines;
        this.rfqs       = rfqs;
        this.rfqLines   = rfqLines;
        this.suppliers  = suppliers;
        this.numberGen  = numberGen;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public SupplierQuoteDto capture(CaptureSupplierQuoteRequest req) {
        Rfq rfq = Lookups.orNotFound(rfqs.findByUid(req.rfqUid()), "Rfq", req.rfqUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, rfq.getCompanyId());

        var supplier = suppliers.findByCompanyIdAndUid(rfq.getCompanyId(), req.supplierUid())
                .orElseThrow(() -> new NotFoundException("Supplier: " + req.supplierUid()));

        String quoteNumber = numberGen.nextSupplierQuote(rfq.getCompanyId());
        SupplierQuote quote = new SupplierQuote(
                rfq.getCompanyId(), rfq.getBranchId(), quoteNumber,
                rfq.getId(), rfq.getUid(),
                supplier.getId(), supplier.getCode(), supplier.getDisplayName(),
                "TZS", actorId());
        quote.setValidUntil(req.validUntil());
        quote.setLeadTimeDays(req.leadTimeDays());
        quote.setNotes(req.notes());
        quote = quotes.save(quote);

        BigDecimal total = BigDecimal.ZERO;
        for (var l : req.lines()) {
            RfqLine rfqLine = rfqLines.findById(l.rfqLineId())
                    .orElseThrow(() -> new NotFoundException("RfqLine: " + l.rfqLineId()));
            short lineNo = (short) (quoteLines.findMaxLineNo(quote.getId()) + 1);
            BigDecimal lineTotal = l.unitPriceAmount()
                    .multiply(l.quotedQty()).setScale(4, RoundingMode.HALF_UP);
            SupplierQuoteLine ql = new SupplierQuoteLine(
                    quote.getId(), rfqLine.getId(), rfqLine.getUid(),
                    rfq.getCompanyId(), rfq.getBranchId(), lineNo,
                    rfqLine.getProductId(), rfqLine.getProductCode(), rfqLine.getProductName(),
                    rfqLine.getUnitId(), rfqLine.getUnitName(),
                    l.quotedQty(), l.quotedQty(),   // qty_in_base = qty (no conversion in quotes)
                    l.unitPriceAmount(), quote.getCurrency(), actorId());
            ql.setLineTotalAmount(lineTotal);
            quoteLines.save(ql);
            total = total.add(lineTotal);
        }
        quote.setQuoteTotalAmount(total.setScale(4, RoundingMode.HALF_UP));

        audit.record(AuditEvent.of(AuditActions.SUPPLIER_QUOTE_CAPTURE, "supplier_quotes",
                quote.getId(), quote.getUid())
                .detail(Map.of("rfqUid", req.rfqUid(), "supplierUid", req.supplierUid())));
        return toDto(quote);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierQuoteDto getByUid(String uid) {
        SupplierQuote q = Lookups.orNotFound(quotes.findByUid(uid), "SupplierQuote", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), q.getCompanyId());
        return toDto(q);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierQuoteDto> listByRfq(String rfqUid, Pageable pageable) {
        Rfq rfq = Lookups.orNotFound(rfqs.findByUid(rfqUid), "Rfq", rfqUid);
        scopeGuard.assertCanActIn(RequestContext.get(), rfq.getCompanyId());
        return quotes.findByRfqId(rfq.getId(), pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal lastQuotedUnitCost(Long companyId, Long supplierId, Long productId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return quoteLines.findLastQuotedUnitCost(companyId, supplierId, productId)
                .orElse(null);
    }

    // -------------------------------------------------------------------------

    private SupplierQuoteDto toDto(SupplierQuote q) {
        List<SupplierQuoteLineDto> lineDtos = quoteLines
                .findBySupplierQuoteIdOrderByLineNo(q.getId())
                .stream().map(SupplierQuoteLineDto::from).toList();
        return SupplierQuoteDto.from(q, lineDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
