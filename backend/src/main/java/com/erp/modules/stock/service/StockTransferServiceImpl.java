package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.dto.StockTransferLineDto;
import com.erp.modules.stock.domain.dto.TransferDispatchedPayload;
import com.erp.modules.stock.domain.dto.TransferReceivedPayload;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.entity.StockTransfer;
import com.erp.modules.stock.domain.entity.StockTransferLine;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.domain.enums.StockTransferMode;
import com.erp.modules.stock.domain.enums.StockTransferStatus;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.repository.StockTransferLineRepository;
import com.erp.modules.stock.repository.StockTransferRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inter-location transfer service (ADR-0028 D-5).
 *
 * <p>Value-preserving (D-2 single avg → net-zero on 1300 → no GL).
 * INSTANT: both movements in the same TX.
 * IN_TRANSIT: dispatch publishes outbox event → handler posts TRANSFER_OUT; receive similarly.
 */
@Service
@Transactional
public class StockTransferServiceImpl implements StockTransferService {

    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final String BASE_CURRENCY = "TZS";

    private final StockTransferRepository      transfers;
    private final StockTransferLineRepository  transferLines;
    private final StockOnHandRepository        onHands;
    private final StockPostingService          posting;
    private final InventoryValuationService    valuation;
    private final ProductService               productService;
    private final LocationResolver             locationResolver;
    private final WarehouseNumberGenerator     numberGenerator;
    private final OutboxPublisher              outbox;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;

    public StockTransferServiceImpl(StockTransferRepository transfers,
                                     StockTransferLineRepository transferLines,
                                     StockOnHandRepository onHands,
                                     StockPostingService posting,
                                     InventoryValuationService valuation,
                                     ProductService productService,
                                     LocationResolver locationResolver,
                                     WarehouseNumberGenerator numberGenerator,
                                     OutboxPublisher outbox,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.transfers        = transfers;
        this.transferLines    = transferLines;
        this.onHands          = onHands;
        this.posting          = posting;
        this.valuation        = valuation;
        this.productService   = productService;
        this.locationResolver = locationResolver;
        this.numberGenerator  = numberGenerator;
        this.outbox           = outbox;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
    }

    @Override
    public StockTransferDto create(CreateStockTransferRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());

        StockLocation srcLoc = locationResolver.resolveLocation(
                request.sourceLocationUid(), principal.companyId());
        StockLocation dstLoc = locationResolver.resolveLocation(
                request.destLocationUid(), principal.companyId());

        if (srcLoc.getId().equals(dstLoc.getId())) {
            throw new IllegalArgumentException("Source and destination locations must be different.");
        }

        String number = numberGenerator.nextTransfer(principal.companyId());
        String mode   = request.transferMode() != null ? request.transferMode().toUpperCase() : "IN_TRANSIT";

        StockTransfer transfer = new StockTransfer(
                principal.companyId(), number, mode,
                srcLoc.getBranchId(), srcLoc.getId(),
                dstLoc.getBranchId(), dstLoc.getId(),
                request.transferDate(), request.notes(), principal.userId());
        transfers.save(transfer);

        short lineNo = 0;
        for (CreateStockTransferRequest.LineRequest lineReq : request.lines()) {
            lineNo++;
            ProductDto product = productService.getByUid(lineReq.productUid());
            StockTransferLine line = new StockTransferLine(
                    transfer.getId(), principal.companyId(), lineNo,
                    product.id(), product.code(), product.name(),
                    null, null,
                    lineReq.qty(), lineReq.qty(), // base qty = transferred qty (assuming base unit)
                    null, BASE_CURRENCY, principal.userId());
            transferLines.save(line);
        }

        audit.record(AuditEvent.of(AuditActions.STOCK_TRANSFER_CREATE, "stock_transfers",
                        transfer.getId(), transfer.getUid())
                .detail(Map.of("number", number, "mode", mode)));

        return toDto(transfer, transferLines.findByStockTransferIdOrderByLineNoAsc(transfer.getId()));
    }

    @Override
    public StockTransferDto completeInstant(String transferUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockTransfer transfer = findAndAssertScope(transferUid, principal);

        if (transfer.getStatus() != StockTransferStatus.DRAFT) {
            throw new IllegalStateException("This transfer is not in DRAFT status.");
        }
        if (transfer.getTransferMode() != StockTransferMode.INSTANT) {
            throw new IllegalStateException("This transfer is not an INSTANT transfer.");
        }

        List<StockTransferLine> lines = transferLines
                .findByStockTransferIdOrderByLineNoAsc(transfer.getId());

        // --- Guard: on-hand / allowNegative check at source before any movement ---
        // allowNegative is a location-level flag (ADR-0028 D-7).
        // The source location was validated on create; look up the flag by PK (scope already trusted).
        boolean srcAllowNegative = locationResolver.isAllowNegative(transfer.getSourceLocationId());

        for (StockTransferLine line : lines) {
            if (!srcAllowNegative) {
                StockOnHand srcSoh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                        principal.companyId(), transfer.getSourceBranchId(),
                        transfer.getSourceLocationId(), line.getProductId())
                        .orElse(null);
                BigDecimal available = srcSoh != null ? srcSoh.availableQty() : BigDecimal.ZERO;
                if (available.compareTo(line.getQtyTransferredBase()) < 0) {
                    throw new ConflictException(
                            "Insufficient stock at source location: available="
                            + available.toPlainString()
                            + ", requested=" + line.getQtyTransferredBase().toPlainString()
                            + ". The source location does not allow negative stock.");
                }
            }
        }

        Instant now = Instant.now();

        for (StockTransferLine line : lines) {
            BigDecimal avgCost = resolveAvgCost(
                    principal.companyId(), line.getProductId());
            BigDecimal value = avgCost != null
                    ? avgCost.multiply(line.getQtyTransferredBase()).setScale(SCALE, RM)
                    : BigDecimal.ZERO;

            // TRANSFER_OUT at source
            posting.post(principal.companyId(), transfer.getSourceBranchId(),
                    transfer.getSourceLocationId(), line.getProductId(),
                    line.getQtyTransferredBase().negate(), MovementType.TRANSFER_OUT,
                    null, "STOCK_TRANSFER", transfer.getUid(),
                    null, null, now, principal.userId(),
                    avgCost, value.negate());

            // TRANSFER_IN at dest
            posting.post(principal.companyId(), transfer.getDestBranchId(),
                    transfer.getDestLocationId(), line.getProductId(),
                    line.getQtyTransferredBase(), MovementType.TRANSFER_IN,
                    null, "STOCK_TRANSFER", transfer.getUid(),
                    null, null, now, principal.userId(),
                    avgCost, value);

            // Move on_hand_value with the units so valuation stays correct (issue #12).
            valuation.transferCost(
                    principal.companyId(),
                    transfer.getSourceBranchId(), transfer.getSourceLocationId(),
                    transfer.getDestBranchId(),   transfer.getDestLocationId(),
                    line.getProductId(), line.getQtyTransferredBase());
        }

        transfer.complete(principal.userId());
        transfers.save(transfer);

        audit.record(AuditEvent.of(AuditActions.STOCK_TRANSFER_COMPLETE, "stock_transfers",
                transfer.getId(), transfer.getUid()).detail(Map.of()));
        return toDto(transfer, lines);
    }

    @Override
    public StockTransferDto dispatch(String transferUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockTransfer transfer = findAndAssertScope(transferUid, principal);

        if (transfer.getStatus() != StockTransferStatus.DRAFT) {
            throw new IllegalStateException("This transfer is not in DRAFT status.");
        }
        if (transfer.getTransferMode() != StockTransferMode.IN_TRANSIT) {
            throw new IllegalStateException("This transfer is not an IN_TRANSIT transfer.");
        }

        List<StockTransferLine> lines = transferLines
                .findByStockTransferIdOrderByLineNoAsc(transfer.getId());

        // --- Guard: on-hand / allowNegative check at source before dispatch ---
        boolean srcAllowNegative = locationResolver.isAllowNegative(transfer.getSourceLocationId());
        if (!srcAllowNegative) {
            for (StockTransferLine line : lines) {
                StockOnHand srcSoh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                        principal.companyId(), transfer.getSourceBranchId(),
                        transfer.getSourceLocationId(), line.getProductId())
                        .orElse(null);
                BigDecimal available = srcSoh != null ? srcSoh.availableQty() : BigDecimal.ZERO;
                if (available.compareTo(line.getQtyTransferredBase()) < 0) {
                    throw new ConflictException(
                            "Insufficient stock at source location: available="
                            + available.toPlainString()
                            + ", requested=" + line.getQtyTransferredBase().toPlainString()
                            + ". The source location does not allow negative stock.");
                }
            }
        }

        transfer.dispatch(principal.userId());
        transfers.save(transfer);

        // Build payload for the outbox event — capture avg cost at dispatch time so the
        // handler can move cost value atomically with the qty movement.
        List<TransferDispatchedPayload.LineItem> lineItems = lines.stream()
                .map(l -> {
                    BigDecimal avg = resolveAvgCost(principal.companyId(), l.getProductId());
                    BigDecimal val = avg != null
                            ? avg.multiply(l.getQtyTransferredBase()).setScale(SCALE, RM)
                            : BigDecimal.ZERO;
                    return new TransferDispatchedPayload.LineItem(
                            l.getProductId(), null, l.getUnitId(),
                            l.getQtyTransferredBase(), avg, val);
                }).toList();

        Long inTransitLocId = locationResolver.inTransitLocationId(
                principal.companyId(), transfer.getDestBranchId());

        TransferDispatchedPayload payload = new TransferDispatchedPayload(
                transfer.getUid(), principal.companyId(),
                transfer.getSourceBranchId(), transfer.getSourceLocationId(),
                inTransitLocId, transfer.getDispatchedAt(), lineItems);

        outbox.publish(DomainEventType.STOCK_TRANSFER_DISPATCHED,
                DomainEventType.AGG_STOCK_TRANSFER,
                transfer.getId(), transfer.getUid(),
                principal.companyId(), transfer.getSourceBranchId(),
                payload);

        audit.record(AuditEvent.of(AuditActions.STOCK_TRANSFER_DISPATCH, "stock_transfers",
                transfer.getId(), transfer.getUid()).detail(Map.of()));
        return toDto(transfer, lines);
    }

    @Override
    public StockTransferDto receive(String transferUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockTransfer transfer = findAndAssertScope(transferUid, principal);

        if (transfer.getStatus() != StockTransferStatus.DISPATCHED) {
            throw new IllegalStateException("This transfer is not in DISPATCHED status.");
        }

        transfer.receive(principal.userId());
        transfers.save(transfer);

        List<StockTransferLine> lines = transferLines
                .findByStockTransferIdOrderByLineNoAsc(transfer.getId());
        List<TransferDispatchedPayload.LineItem> lineItems = lines.stream()
                .map(l -> {
                    BigDecimal avg = resolveAvgCost(principal.companyId(), l.getProductId());
                    BigDecimal val = avg != null
                            ? avg.multiply(l.getQtyTransferredBase()).setScale(SCALE, RM)
                            : BigDecimal.ZERO;
                    return new TransferDispatchedPayload.LineItem(
                            l.getProductId(), null, l.getUnitId(),
                            l.getQtyTransferredBase(), avg, val);
                }).toList();

        Long inTransitLocId = locationResolver.inTransitLocationId(
                principal.companyId(), transfer.getDestBranchId());

        TransferReceivedPayload payload = new TransferReceivedPayload(
                transfer.getUid(), principal.companyId(),
                transfer.getDestBranchId(), transfer.getDestLocationId(),
                inTransitLocId, transfer.getReceivedAt(), lineItems);

        outbox.publish(DomainEventType.STOCK_TRANSFER_RECEIVED,
                DomainEventType.AGG_STOCK_TRANSFER,
                transfer.getId(), transfer.getUid(),
                principal.companyId(), transfer.getDestBranchId(),
                payload);

        audit.record(AuditEvent.of(AuditActions.STOCK_TRANSFER_RECEIVE, "stock_transfers",
                transfer.getId(), transfer.getUid()).detail(Map.of()));
        return toDto(transfer, lines);
    }

    @Override
    public StockTransferDto cancel(String transferUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockTransfer transfer = findAndAssertScope(transferUid, principal);

        if (transfer.getStatus() != StockTransferStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT transfers can be cancelled.");
        }
        transfer.cancel(principal.userId());
        transfers.save(transfer);

        audit.record(AuditEvent.of(AuditActions.STOCK_TRANSFER_CANCEL, "stock_transfers",
                transfer.getId(), transfer.getUid()).detail(Map.of()));
        return toDto(transfer, transferLines.findByStockTransferIdOrderByLineNoAsc(transfer.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public StockTransferDto getByUid(String transferUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockTransfer transfer = findAndAssertScope(transferUid, principal);
        return toDto(transfer, transferLines.findByStockTransferIdOrderByLineNoAsc(transfer.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransferDto> list(Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());
        return transfers.findByCompanyId(principal.companyId(), pageable)
                .map(t -> toDto(t, List.of()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StockTransfer findAndAssertScope(String uid, RequestContext.Principal principal) {
        StockTransfer t = transfers.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockTransfer", uid));
        scopeGuard.assertCanActIn(principal, t.getCompanyId());
        return t;
    }

    private BigDecimal resolveAvgCost(Long companyId, Long productId) {
        // The company-product avg_cost is the same across all location rows (D-2).
        // Find any on-hand row for this product in this company to get the running average.
        return onHands.findByCompanyIdAndProductId(companyId, productId)
                .stream()
                .filter(soh -> soh.getAvgCost() != null)
                .findFirst()
                .map(StockOnHand::getAvgCost)
                .orElse(null);
    }

    private static StockTransferDto toDto(StockTransfer t, List<StockTransferLine> lines) {
        List<StockTransferLineDto> lineDtos = lines.stream()
                .map(l -> new StockTransferLineDto(
                        l.getId(), l.getUid(), l.getLineNo(),
                        l.getProductId(), l.getProductCode(), l.getProductName(),
                        l.getUnitName(), l.getQtyTransferred(), l.getQtyTransferredBase(),
                        l.getValueAmount(), l.getCurrency()))
                .toList();
        return new StockTransferDto(
                t.getId(), t.getUid(), t.getCompanyId(), t.getTransferNumber(),
                t.getStatus(), t.getTransferMode(),
                t.getSourceBranchId(), t.getSourceLocationId(),
                t.getDestBranchId(), t.getDestLocationId(),
                t.getTransferDate(), t.getExpectedArrivalDate(),
                t.getDispatchedAt(), t.getDispatchedBy(),
                t.getReceivedAt(), t.getReceivedBy(),
                t.getNotes(), lineDtos);
    }
}
