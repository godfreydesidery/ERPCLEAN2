package com.erp.modules.purchases.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.CreatePurchaseRequisitionRequest;
import com.erp.modules.purchases.domain.dto.PurchaseRequisitionDto;
import com.erp.modules.purchases.domain.dto.PurchaseRequisitionLineDto;
import com.erp.modules.purchases.domain.entity.PurchaseRequisition;
import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import com.erp.modules.purchases.domain.enums.RequisitionStatus;
import com.erp.modules.purchases.repository.PurchaseRequisitionLineRepository;
import com.erp.modules.purchases.repository.PurchaseRequisitionRepository;
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
public class PurchaseRequisitionServiceImpl implements PurchaseRequisitionService {

    private final PurchaseRequisitionRepository     requisitions;
    private final PurchaseRequisitionLineRepository reqLines;
    private final CompanyRepository                 companies;
    private final ProductRepository                 products;
    private final UnitOfMeasureRepository           units;
    private final PurchaseNumberGenerator           numberGen;
    private final ScopeGuard                        scopeGuard;
    private final AuditService                      audit;

    public PurchaseRequisitionServiceImpl(PurchaseRequisitionRepository requisitions,
                                           PurchaseRequisitionLineRepository reqLines,
                                           CompanyRepository companies,
                                           ProductRepository products,
                                           UnitOfMeasureRepository units,
                                           PurchaseNumberGenerator numberGen,
                                           ScopeGuard scopeGuard,
                                           AuditService audit) {
        this.requisitions = requisitions;
        this.reqLines     = reqLines;
        this.companies    = companies;
        this.products     = products;
        this.units        = units;
        this.numberGen    = numberGen;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
    }

    @Override
    public PurchaseRequisitionDto create(CreatePurchaseRequisitionRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = branchId(ctx);

        PurchaseRequisition r = new PurchaseRequisition(
                companyId, branchId, req.requiredByDate(),
                req.costCentreCode(), req.notes(), actorId());
        r = requisitions.save(r);

        for (var l : req.lines()) {
            // SECURITY: resolve product/unit SCOPED to the requisition's company so a caller
            // cannot embed a foreign-company product id and read its code/name back (confused-deputy).
            Product product = products.findByCompanyIdAndId(companyId, l.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            UnitOfMeasure unit = units.findByCompanyIdAndId(companyId, l.unitId())
                    .orElseThrow(() -> new NotFoundException("Unit of measure not found"));
            short lineNo = (short) (reqLines.findMaxLineNo(r.getId()) + 1);
            PurchaseRequisitionLine line = new PurchaseRequisitionLine(
                    r.getId(), companyId, branchId, lineNo,
                    product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(),
                    l.requestedQty(), l.requestedQty(), // base qty == req qty (no conversion in req)
                    l.estimatedUnitCost(), l.note(), null, actorId());
            reqLines.save(line);
        }

        audit.record(AuditEvent.of(AuditActions.REQUISITION_CREATE, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("companyId", companyId.toString())));
        return toDto(r);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseRequisitionDto getByUid(String uid) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        return toDto(r);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseRequisitionDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return requisitions.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public PurchaseRequisitionDto submit(String uid) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        assertStatus(r, RequisitionStatus.DRAFT, "submit");

        String number = numberGen.nextRequisition(r.getCompanyId());
        r.setRequisitionNumber(number);
        r.setStatus(RequisitionStatus.SUBMITTED);
        r.setSubmittedAt(Instant.now());
        r.setSubmittedBy(actorId());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.REQUISITION_SUBMIT, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("number", number)));
        return toDto(r);
    }

    @Override
    public PurchaseRequisitionDto approve(String uid) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        assertStatus(r, RequisitionStatus.SUBMITTED, "approve");

        r.setStatus(RequisitionStatus.APPROVED);
        r.setApprovedAt(Instant.now());
        r.setApprovedBy(actorId());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.REQUISITION_APPROVE, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("number", r.getRequisitionNumber())));
        return toDto(r);
    }

    @Override
    public PurchaseRequisitionDto reject(String uid, String reason) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        assertStatus(r, RequisitionStatus.SUBMITTED, "reject");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required.");
        }
        r.setStatus(RequisitionStatus.REJECTED);
        r.setRejectedAt(Instant.now());
        r.setRejectedBy(actorId());
        r.setRejectReason(reason);
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.REQUISITION_REJECT, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("reason", reason)));
        return toDto(r);
    }

    @Override
    public String convert(String uid, String convertToType) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        assertStatus(r, RequisitionStatus.APPROVED, "convert");

        // Conversion creates the target document; return its uid.
        // Actual creation is done by caller (controller) using the appropriate service.
        // This method just marks the requisition as CONVERTED.
        if (!"PURCHASE_ORDER".equals(convertToType) && !"RFQ".equals(convertToType)) {
            throw new IllegalArgumentException("convertToType must be PURCHASE_ORDER or RFQ");
        }
        r.setStatus(RequisitionStatus.CONVERTED);
        r.setConvertedToType(convertToType);
        r.setConvertedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.REQUISITION_CONVERT, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("convertToType", convertToType)));
        return r.getUid();   // caller links the uid of the created document separately
    }

    @Override
    public PurchaseRequisitionDto cancel(String uid, String reason) {
        PurchaseRequisition r = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());

        if (r.getStatus() != RequisitionStatus.DRAFT && r.getStatus() != RequisitionStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Can only cancel DRAFT or SUBMITTED requisitions; current: " + r.getStatus());
        }

        // PURCHASES-027: chk_purchase_requisition_number_when_submitted requires
        // requisition_number IS NOT NULL for any status != DRAFT.  A DRAFT has no number yet, so
        // assign one before changing the status to CANCELLED.
        if (r.getStatus() == RequisitionStatus.DRAFT && r.getRequisitionNumber() == null) {
            r.setRequisitionNumber(numberGen.nextRequisition(r.getCompanyId()));
        }
        r.setStatus(RequisitionStatus.CANCELLED);
        r.setCancelledAt(Instant.now());
        r.setCancelReason(reason);
        r.setUpdatedAt(Instant.now());
        r.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.REQUISITION_CANCEL, "purchase_requisitions",
                r.getId(), r.getUid()).detail(Map.of("reason", reason != null ? reason : "")));
        return toDto(r);
    }

    // -------------------------------------------------------------------------

    private PurchaseRequisition require(String uid) {
        return Lookups.orNotFound(requisitions.findByUid(uid), "PurchaseRequisition", uid);
    }

    private void assertStatus(PurchaseRequisition r, RequisitionStatus expected, String op) {
        if (r.getStatus() != expected) {
            throw new IllegalStateException(
                    "Cannot " + op + " a requisition in status " + r.getStatus()
                            + "; expected " + expected);
        }
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + companyUid));
    }

    private PurchaseRequisitionDto toDto(PurchaseRequisition r) {
        List<PurchaseRequisitionLineDto> lineDtos = reqLines
                .findByPurchaseRequisitionIdOrderByLineNo(r.getId())
                .stream().map(PurchaseRequisitionLineDto::from).toList();
        return PurchaseRequisitionDto.from(r, lineDtos);
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
