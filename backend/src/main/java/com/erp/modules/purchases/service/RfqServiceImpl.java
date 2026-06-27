package com.erp.modules.purchases.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.CreateRfqRequest;
import com.erp.modules.purchases.domain.dto.RfqDto;
import com.erp.modules.purchases.domain.dto.RfqLineDto;
import com.erp.modules.purchases.domain.entity.Rfq;
import com.erp.modules.purchases.domain.entity.RfqLine;
import com.erp.modules.purchases.domain.entity.RfqSupplier;
import com.erp.modules.purchases.domain.entity.SupplierQuote;
import com.erp.modules.purchases.domain.enums.RfqStatus;
import com.erp.modules.purchases.domain.enums.SupplierQuoteStatus;
import com.erp.modules.purchases.repository.RfqLineRepository;
import com.erp.modules.purchases.repository.RfqRepository;
import com.erp.modules.purchases.repository.RfqSupplierRepository;
import com.erp.modules.purchases.repository.SupplierQuoteRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RfqServiceImpl implements RfqService {

    private final RfqRepository             rfqs;
    private final RfqLineRepository         rfqLines;
    private final RfqSupplierRepository     rfqSuppliers;
    private final SupplierQuoteRepository   quotes;
    private final SupplierRepository        suppliers;
    private final ProductRepository         products;
    private final UnitOfMeasureRepository   units;
    private final CompanyRepository         companies;
    private final PurchaseNumberGenerator   numberGen;
    private final PurchaseOrderService      poService;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public RfqServiceImpl(RfqRepository rfqs,
                           RfqLineRepository rfqLines,
                           RfqSupplierRepository rfqSuppliers,
                           SupplierQuoteRepository quotes,
                           SupplierRepository suppliers,
                           ProductRepository products,
                           UnitOfMeasureRepository units,
                           CompanyRepository companies,
                           PurchaseNumberGenerator numberGen,
                           PurchaseOrderService poService,
                           ScopeGuard scopeGuard,
                           AuditService audit) {
        this.rfqs         = rfqs;
        this.rfqLines     = rfqLines;
        this.rfqSuppliers = rfqSuppliers;
        this.quotes       = quotes;
        this.suppliers    = suppliers;
        this.products     = products;
        this.units        = units;
        this.companies    = companies;
        this.numberGen    = numberGen;
        this.poService    = poService;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
    }

    @Override
    public RfqDto create(CreateRfqRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = branchId(ctx);

        String rfqNumber = numberGen.nextRfq(companyId);
        Rfq rfq = new Rfq(companyId, branchId, rfqNumber,
                req.sourceRequisitionUid(), req.responseDueDate(), req.notes(), actorId());
        rfq = rfqs.save(rfq);

        // Lines
        for (var l : req.lines()) {
            // SECURITY: resolve product/unit SCOPED to the RFQ's company so a caller
            // cannot embed a foreign-company product id and read its code/name back (confused-deputy).
            Product product = products.findByCompanyIdAndId(companyId, l.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            UnitOfMeasure unit = units.findByCompanyIdAndId(companyId, l.unitId())
                    .orElseThrow(() -> new NotFoundException("Unit of measure not found"));
            short lineNo = (short) (rfqLines.findMaxLineNo(rfq.getId()) + 1);
            rfqLines.save(new RfqLine(rfq.getId(), companyId, branchId, lineNo,
                    product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(), l.quantity(), l.quantity(), actorId()));
        }

        // Invite suppliers
        final Long savedRfqId      = rfq.getId();
        final Long finalCompanyId  = companyId;
        final Long finalBranchId   = branchId;
        for (String supplierUid : req.supplierUids()) {
            suppliers.findByCompanyIdAndUid(finalCompanyId, supplierUid).ifPresent(s -> {
                if (!rfqSuppliers.existsByRfqIdAndSupplierId(savedRfqId, s.getId())) {
                    rfqSuppliers.save(new RfqSupplier(savedRfqId, s.getId(), finalCompanyId,
                            finalBranchId, actorId()));
                }
            });
        }

        audit.record(AuditEvent.of(AuditActions.RFQ_CREATE, "rfqs",
                rfq.getId(), rfq.getUid()).detail(Map.of("rfqNumber", rfqNumber)));
        return toDto(rfq);
    }

    @Override
    @Transactional(readOnly = true)
    public RfqDto getByUid(String uid) {
        Rfq rfq = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rfq.getCompanyId());
        return toDto(rfq);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RfqDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return rfqs.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public RfqDto send(String uid) {
        Rfq rfq = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rfq.getCompanyId());
        if (rfq.getStatus() != RfqStatus.DRAFT) {
            throw new IllegalStateException("Can only send a DRAFT RFQ; current: " + rfq.getStatus());
        }
        rfq.setStatus(RfqStatus.SENT);
        rfq.setSentAt(Instant.now());
        rfq.setUpdatedAt(Instant.now());
        rfq.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.RFQ_SEND, "rfqs",
                rfq.getId(), rfq.getUid()).detail(Map.of("rfqNumber", rfq.getRfqNumber())));
        return toDto(rfq);
    }

    @Override
    public RfqDto award(String uid, String winningQuoteUid) {
        Rfq rfq = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rfq.getCompanyId());
        if (rfq.getStatus() != RfqStatus.SENT) {
            throw new IllegalStateException("Can only award a SENT RFQ; current: " + rfq.getStatus());
        }

        SupplierQuote winningQuote = quotes.findByUid(winningQuoteUid)
                .orElseThrow(() -> new NotFoundException("SupplierQuote: " + winningQuoteUid));
        if (!winningQuote.getRfqId().equals(rfq.getId())) {
            throw new IllegalArgumentException("Quote " + winningQuoteUid + " does not belong to RFQ " + uid);
        }

        // Mark winning quote AWARDED, others NOT_AWARDED
        quotes.findByRfqId(rfq.getId()).forEach(q -> {
            if (q.getUid().equals(winningQuoteUid)) {
                q.setStatus(SupplierQuoteStatus.AWARDED);
            } else if (q.getStatus() == SupplierQuoteStatus.RECEIVED) {
                q.setStatus(SupplierQuoteStatus.NOT_AWARDED);
            }
            q.setUpdatedAt(Instant.now());
            q.setUpdatedBy(actorId());
        });

        // Create PO from the winning quote
        var po = poService.createFromQuote(winningQuoteUid);

        rfq.setStatus(RfqStatus.AWARDED);
        rfq.setAwardedQuoteUid(winningQuoteUid);
        rfq.setAwardedPoUid(po.uid());
        rfq.setAwardedAt(Instant.now());
        rfq.setUpdatedAt(Instant.now());
        rfq.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.RFQ_AWARD, "rfqs",
                rfq.getId(), rfq.getUid())
                .detail(Map.of("winningQuoteUid", winningQuoteUid, "poUid", po.uid())));
        return toDto(rfq);
    }

    @Override
    public RfqDto cancel(String uid) {
        Rfq rfq = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rfq.getCompanyId());
        if (rfq.getStatus() != RfqStatus.DRAFT && rfq.getStatus() != RfqStatus.SENT) {
            throw new IllegalStateException(
                    "Can only cancel DRAFT or SENT RFQs; current: " + rfq.getStatus());
        }
        rfq.setStatus(RfqStatus.CANCELLED);
        rfq.setCancelledAt(Instant.now());
        rfq.setUpdatedAt(Instant.now());
        rfq.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.RFQ_CANCEL, "rfqs",
                rfq.getId(), rfq.getUid()).detail(Map.of()));
        return toDto(rfq);
    }

    // -------------------------------------------------------------------------

    private Rfq require(String uid) {
        return Lookups.orNotFound(rfqs.findByUid(uid), "Rfq", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + companyUid));
    }

    private RfqDto toDto(Rfq rfq) {
        List<RfqLineDto> lineDtos = rfqLines.findByRfqIdOrderByLineNo(rfq.getId())
                .stream().map(RfqLineDto::from).toList();
        List<Long> supplierIds = rfqSuppliers.findByRfqId(rfq.getId())
                .stream().map(rs -> rs.getSupplierId()).toList();
        // Resolve invited suppliers to their real uids (company-scoped) so the UI shows
        // code + name instead of a raw "id:" placeholder.
        List<String> supplierUids = supplierIds.isEmpty()
                ? List.of()
                : suppliers.findUidsByCompanyIdAndIdIn(rfq.getCompanyId(), supplierIds);
        return RfqDto.from(rfq, lineDtos, supplierUids);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchId(RequestContext.Principal ctx) {
        Long id = ctx != null ? ctx.branchId() : null;
        if (id == null) throw new IllegalStateException("No active branch in context.");
        return id;
    }
}
