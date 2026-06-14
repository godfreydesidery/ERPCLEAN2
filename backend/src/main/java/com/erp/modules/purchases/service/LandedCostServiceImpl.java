package com.erp.modules.purchases.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.CreateLandedCostRequest;
import com.erp.modules.purchases.domain.dto.LandedCostAllocatedPayload;
import com.erp.modules.purchases.domain.dto.LandedCostChargeDto;
import com.erp.modules.purchases.domain.dto.LandedCostDto;
import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.modules.purchases.domain.entity.LandedCost;
import com.erp.modules.purchases.domain.entity.LandedCostAllocation;
import com.erp.modules.purchases.domain.entity.LandedCostCharge;
import com.erp.modules.purchases.domain.entity.LandedCostReceipt;
import com.erp.modules.purchases.domain.enums.LandedCostBasis;
import com.erp.modules.purchases.domain.enums.LandedCostStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.LandedCostAllocationRepository;
import com.erp.modules.purchases.repository.LandedCostChargeRepository;
import com.erp.modules.purchases.repository.LandedCostReceiptRepository;
import com.erp.modules.purchases.repository.LandedCostRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LandedCostServiceImpl implements LandedCostService {

    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final String DEFAULT_CURRENCY = "TZS";

    private final LandedCostRepository           landedCosts;
    private final LandedCostReceiptRepository     lcReceipts;
    private final LandedCostChargeRepository      lcCharges;
    private final LandedCostAllocationRepository  lcAllocations;
    private final GoodsReceiptRepository          grRepo;
    private final GoodsReceiptLineRepository      grLineRepo;
    private final CompanyRepository               companies;
    private final PurchaseNumberGenerator         numberGen;
    private final OutboxPublisher                 outbox;
    private final ScopeGuard                      scopeGuard;
    private final AuditService                    audit;

    public LandedCostServiceImpl(LandedCostRepository landedCosts,
                                  LandedCostReceiptRepository lcReceipts,
                                  LandedCostChargeRepository lcCharges,
                                  LandedCostAllocationRepository lcAllocations,
                                  GoodsReceiptRepository grRepo,
                                  GoodsReceiptLineRepository grLineRepo,
                                  CompanyRepository companies,
                                  PurchaseNumberGenerator numberGen,
                                  OutboxPublisher outbox,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.landedCosts   = landedCosts;
        this.lcReceipts    = lcReceipts;
        this.lcCharges     = lcCharges;
        this.lcAllocations = lcAllocations;
        this.grRepo        = grRepo;
        this.grLineRepo    = grLineRepo;
        this.companies     = companies;
        this.numberGen     = numberGen;
        this.outbox        = outbox;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    @Override
    public LandedCostDto create(CreateLandedCostRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = branchId(ctx);

        String lcNumber = numberGen.nextLandedCost(companyId);
        LandedCost lc = new LandedCost(companyId, branchId, lcNumber,
                req.basis(), DEFAULT_CURRENCY, req.notes(), actorId());
        lc = landedCosts.save(lc);

        // Link receipts
        for (String grUid : req.receiptUids()) {
            GoodsReceipt gr = grRepo.findByCompanyIdAndUid(companyId, grUid)
                    .orElseThrow(() -> new NotFoundException("GoodsReceipt: " + grUid));
            if (!lcReceipts.existsByLandedCostIdAndGoodsReceiptId(lc.getId(), gr.getId())) {
                lcReceipts.save(new LandedCostReceipt(lc.getId(), gr.getId(), gr.getUid(),
                        companyId, branchId, actorId()));
            }
        }

        // Charges
        BigDecimal totalCharge = BigDecimal.ZERO;
        for (var c : req.charges()) {
            short lineNo = (short) (lcCharges.findMaxLineNo(lc.getId()) + 1);
            LandedCostCharge charge = new LandedCostCharge(
                    lc.getId(), companyId, branchId, lineNo,
                    c.chargeType(), c.amount(), false, null, DEFAULT_CURRENCY, actorId());
            lcCharges.save(charge);
            totalCharge = totalCharge.add(c.amount());
        }
        lc.setTotalChargeAmount(totalCharge.setScale(SCALE, RM));
        lc.setUpdatedAt(Instant.now());
        lc.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.LANDED_COST_CREATE, "landed_costs",
                lc.getId(), lc.getUid()).detail(Map.of("lcNumber", lcNumber)));
        return toDto(lc);
    }

    @Override
    @Transactional(readOnly = true)
    public LandedCostDto getByUid(String uid) {
        LandedCost lc = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lc.getCompanyId());
        return toDto(lc);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LandedCostDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return landedCosts.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public LandedCostDto confirm(String uid) {
        LandedCost lc = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lc.getCompanyId());
        if (lc.getStatus() != LandedCostStatus.DRAFT) {
            throw new IllegalStateException(
                    "Can only confirm a DRAFT landed cost; current: " + lc.getStatus());
        }

        // Collect all GR lines across all linked receipts
        List<LandedCostReceipt> linked = lcReceipts.findByLandedCostId(lc.getId());
        List<GoodsReceiptLine> allGrLines = new ArrayList<>();
        for (LandedCostReceipt lcr : linked) {
            allGrLines.addAll(grLineRepo.findByGoodsReceiptIdOrderByLineNo(lcr.getGoodsReceiptId()));
        }
        if (allGrLines.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot confirm landed cost with no linked GR lines.");
        }

        BigDecimal totalCharge = lc.getTotalChargeAmount();
        if (totalCharge == null || totalCharge.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException("Landed cost total charge is zero.");
        }

        // Compute allocation basis weight for each GR line
        BigDecimal basisTotal = computeBasisTotal(lc.getBasis(), allGrLines);
        if (basisTotal.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalStateException(
                    "Basis total is zero — cannot allocate landed cost (divide by zero).");
        }

        List<LandedCostAllocatedPayload.AllocationLine> payloadLines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;

        for (int i = 0; i < allGrLines.size(); i++) {
            GoodsReceiptLine grLine = allGrLines.get(i);
            BigDecimal lineWeight = getBasisWeight(lc.getBasis(), grLine);
            BigDecimal share;

            if (i == allGrLines.size() - 1) {
                // Last line gets the remainder to avoid rounding drift
                share = totalCharge.subtract(allocated).setScale(SCALE, RM);
            } else {
                share = totalCharge.multiply(lineWeight)
                        .divide(basisTotal, SCALE, RM);
            }
            allocated = allocated.add(share);

            LandedCostAllocation alloc = new LandedCostAllocation(
                    lc.getId(), grLine.getId(), grLine.getUid(),
                    grLine.getProductId(),
                    lc.getCompanyId(), lc.getBranchId(),
                    share, DEFAULT_CURRENCY, actorId());
            lcAllocations.save(alloc);

            payloadLines.add(new LandedCostAllocatedPayload.AllocationLine(
                    grLine.getId(), grLine.getUid(), grLine.getProductId(), share));
        }

        lc.setStatus(LandedCostStatus.CONFIRMED);
        lc.setConfirmedAt(Instant.now());
        lc.setConfirmedBy(actorId());
        lc.setUpdatedAt(Instant.now());
        lc.setUpdatedBy(actorId());

        // Publish outbox event — stock handler will update avg_cost + post GL
        LandedCostAllocatedPayload payload = new LandedCostAllocatedPayload(
                lc.getUid(), lc.getCompanyId(), lc.getBranchId(),
                totalCharge, DEFAULT_CURRENCY, payloadLines,
                lc.getLandedCostNumber());
        outbox.publish(DomainEventType.LANDED_COST_ALLOCATED,
                DomainEventType.AGG_LANDED_COST,
                lc.getId(), lc.getUid(),
                lc.getCompanyId(), lc.getBranchId(), payload);

        audit.record(AuditEvent.of(AuditActions.LANDED_COST_CONFIRM, "landed_costs",
                lc.getId(), lc.getUid())
                .detail(Map.of("totalCharge", totalCharge.toPlainString(),
                        "allocatedLines", String.valueOf(allGrLines.size()))));
        return toDto(lc);
    }

    // -------------------------------------------------------------------------

    private BigDecimal computeBasisTotal(LandedCostBasis basis, List<GoodsReceiptLine> lines) {
        return lines.stream()
                .map(l -> getBasisWeight(basis, l))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal getBasisWeight(LandedCostBasis basis, GoodsReceiptLine l) {
        return switch (basis) {
            case BY_VALUE    -> l.getLineCostAmount() != null ? l.getLineCostAmount() : BigDecimal.ZERO;
            case BY_QUANTITY -> l.getQtyInBase() != null ? l.getQtyInBase() : BigDecimal.ZERO;
        };
    }

    private LandedCost require(String uid) {
        return Lookups.orNotFound(landedCosts.findByUid(uid), "LandedCost", uid);
    }

    private LandedCostDto toDto(LandedCost lc) {
        List<String> receiptUids = lcReceipts.findByLandedCostId(lc.getId())
                .stream().map(LandedCostReceipt::getGoodsReceiptUid).toList();
        List<LandedCostChargeDto> chargeDtos = lcCharges.findByLandedCostIdOrderByLineNo(lc.getId())
                .stream().map(LandedCostChargeDto::from).toList();
        return LandedCostDto.from(lc, receiptUids, chargeDtos);
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
