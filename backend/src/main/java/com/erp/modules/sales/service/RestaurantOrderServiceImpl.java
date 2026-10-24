package com.erp.modules.sales.service;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.BillRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CancelRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.MergeRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.RestaurantSplitResultDto;
import com.erp.modules.sales.domain.dto.SplitRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PayRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.PrepSlipDto;
import com.erp.modules.sales.domain.dto.RestaurantBillDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderLineDto;
import com.erp.modules.sales.domain.dto.RestaurantPaymentDto;
import com.erp.modules.sales.domain.dto.RestaurantSendResultDto;
import com.erp.modules.sales.domain.dto.RestaurantVoidResultDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.VoidRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.entity.PrepStation;
import com.erp.modules.sales.domain.entity.PrepStationCategory;
import com.erp.modules.sales.domain.entity.RestaurantOrder;
import com.erp.modules.sales.domain.entity.RestaurantOrderLine;
import com.erp.modules.sales.domain.entity.RestaurantTable;
import com.erp.modules.sales.domain.enums.PosSaleLookupVerdict;
import com.erp.modules.sales.domain.enums.PrepSlipKind;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PrepStationCategoryRepository;
import com.erp.modules.sales.repository.PrepStationRepository;
import com.erp.modules.sales.repository.RestaurantOrderLineRepository;
import com.erp.modules.sales.repository.RestaurantOrderRepository;
import com.erp.modules.sales.repository.RestaurantTableRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RestaurantOrderServiceImpl implements RestaurantOrderService {

    /** The authority a void (or a cancel with sent items) needs — held, or proven by step-up. */
    static final String VOID_PERMISSION = "RESTAURANT.ORDER.VOID";

    private static final List<RestaurantOrderStatus> LIVE =
            List.of(RestaurantOrderStatus.OPEN, RestaurantOrderStatus.BILLED);

    /** Stands in for a user IAM can no longer name — never an id on a slip. */
    private static final String UNNAMED = "Staff";

    private final RestaurantOrderRepository orders;
    private final RestaurantOrderLineRepository lines;
    private final RestaurantTableRepository tables;
    private final PrepStationRepository stations;
    private final PrepStationCategoryRepository routes;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final ProductBulkPackRepository bulkPacks;
    private final CustomerRepository customers;
    private final PosSessionRepository sessions;
    private final PosSaleService posSales;
    private final SalesInvoiceService invoices;
    private final RestaurantBillCalculator calculator;
    private final SalesDepthNumberGenerator numberGen;
    private final RestaurantScope scope;
    private final PermissionResolver permissionResolver;
    private final StepUpAuthService stepUpAuth;
    private final AppUserRepository users;
    private final UserLookupService userLookup;
    private final AuditService audit;

    public RestaurantOrderServiceImpl(RestaurantOrderRepository orders,
                                      RestaurantOrderLineRepository lines,
                                      RestaurantTableRepository tables,
                                      PrepStationRepository stations,
                                      PrepStationCategoryRepository routes,
                                      ProductRepository products,
                                      UnitOfMeasureRepository units,
                                      ProductBulkPackRepository bulkPacks,
                                      CustomerRepository customers,
                                      PosSessionRepository sessions,
                                      PosSaleService posSales,
                                      SalesInvoiceService invoices,
                                      RestaurantBillCalculator calculator,
                                      SalesDepthNumberGenerator numberGen,
                                      RestaurantScope scope,
                                      PermissionResolver permissionResolver,
                                      StepUpAuthService stepUpAuth,
                                      AppUserRepository users,
                                      UserLookupService userLookup,
                                      AuditService audit) {
        this.orders             = orders;
        this.lines              = lines;
        this.tables             = tables;
        this.stations           = stations;
        this.routes             = routes;
        this.products           = products;
        this.units              = units;
        this.bulkPacks          = bulkPacks;
        this.customers          = customers;
        this.sessions           = sessions;
        this.posSales           = posSales;
        this.invoices           = invoices;
        this.calculator         = calculator;
        this.numberGen          = numberGen;
        this.scope              = scope;
        this.permissionResolver = permissionResolver;
        this.stepUpAuth         = stepUpAuth;
        this.users              = users;
        this.userLookup         = userLookup;
        this.audit              = audit;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<RestaurantOrderDto> list(String branchUid, List<RestaurantOrderStatus> statuses,
                                         String tableUid) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(branchUid);
        Collection<RestaurantOrderStatus> wanted =
                statuses == null || statuses.isEmpty() ? LIVE : statuses;
        List<RestaurantOrder> found;
        if (tableUid == null || tableUid.isBlank()) {
            found = orders.findByCompanyIdAndBranchIdAndStatusInOrderByOpenedAtAsc(
                    companyId, branchId, wanted);
        } else {
            RestaurantTable table = requireTable(companyId, branchId, tableUid, false);
            found = orders.findByCompanyIdAndBranchIdAndRestaurantTableIdAndStatusInOrderByOpenedAtAsc(
                    companyId, branchId, table.getId(), wanted);
        }
        return toDtos(found);
    }

    @Override
    @Transactional(readOnly = true)
    public RestaurantOrderDto get(String uid) {
        var order = orders.findByCompanyIdAndUid(scope.companyId(), uid)
                .orElseThrow(() -> NotFoundException.of("RestaurantOrder", uid));
        scope.assertCanActOn(order.getCompanyId(), order.getBranchId());
        return toDto(order);
    }

    // -------------------------------------------------------------------------
    // Order header
    // -------------------------------------------------------------------------

    @Override
    public RestaurantOrderDto open(CreateRestaurantOrderRequest req) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(null);
        RestaurantOrderType type = req.orderType() != null ? req.orderType() : RestaurantOrderType.DINE_IN;
        Long tableId = null;
        if (type == RestaurantOrderType.DINE_IN) {
            if (req.tableUid() == null || req.tableUid().isBlank()) {
                throw new IllegalArgumentException("Choose a table for a dine-in order.");
            }
            tableId = requireTable(companyId, branchId, req.tableUid(), true).getId();
        }
        var order = new RestaurantOrder(companyId, branchId, numberGen.nextRestaurantOrder(companyId),
                type, tableId, scope.actorId());
        order.setGuestCount(req.guestCount());
        order.setNotes(blankToNull(req.notes()));
        order = orders.save(order);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_OPEN, "restaurant_orders",
                order.getId(), order.getUid())
                .detail(Map.of("orderNumber", order.getOrderNumber(), "type", type.name())));
        return toDto(order);
    }

    @Override
    public RestaurantOrderDto update(String uid, UpdateRestaurantOrderRequest req) {
        var order = lockLive(uid);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (req.tableUid() != null && !req.tableUid().isBlank()) {
            var table = requireTable(order.getCompanyId(), order.getBranchId(), req.tableUid(), true);
            if (!table.getId().equals(order.getRestaurantTableId())) {
                order.moveToTable(table.getId());
                detail.put("movedToTable", table.getName());
            }
        }
        if (req.guestCount() != null) {
            order.setGuestCount(req.guestCount());
        }
        if (req.notes() != null) {
            order.setNotes(blankToNull(req.notes()));
        }
        touch(order);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_UPDATE, "restaurant_orders",
                order.getId(), order.getUid()).detail(detail));
        return toDto(order);
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    @Override
    public RestaurantOrderDto addLine(String uid, AddRestaurantOrderLineRequest req) {
        var order = lockLive(uid);
        Product product = products.findByCompanyIdAndId(order.getCompanyId(), req.productId())
                .orElseThrow(() -> new NotFoundException("This menu item was not found."));
        if (!product.isSellable() || product.getStatus() != MasterStatus.ACTIVE) {
            throw new IllegalArgumentException(product.getName() + " is not for sale.");
        }
        UnitOfMeasure unit = units.findByCompanyIdAndId(order.getCompanyId(), req.unitId())
                .orElseThrow(() -> new NotFoundException("This unit was not found."));
        boolean baseUnit = product.getBaseUnit() != null
                && unit.getId().equals(product.getBaseUnit().getId());
        if (!baseUnit && bulkPacks.findByProductIdAndUnitId(product.getId(), unit.getId()).isEmpty()) {
            throw new IllegalArgumentException(
                    product.getName() + " is not sold in " + unit.getName() + ".");
        }
        BigDecimal qty = req.quantity() != null ? req.quantity() : BigDecimal.ONE;
        String note = blankToNull(req.note());
        Long actor = scope.actorId();
        Instant now = Instant.now();

        // Tapping the same item again adds to its unsent line — unless a note makes it different
        // ("one tea, no sugar" is not the same as "one tea").
        var existing = note != null ? null : lines.findByRestaurantOrderIdOrderByLineNoAsc(order.getId())
                .stream()
                .filter(l -> l.isPending() && l.getNote() == null
                        && l.getProductId().equals(product.getId())
                        && l.getUnitId().equals(unit.getId()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.changePending(existing.getQuantity().add(qty), null, actor, now);
        } else {
            lines.save(new RestaurantOrderLine(order, lines.findMaxLineNo(order.getId()) + 1,
                    product.getId(), unit.getId(), qty, note, actor));
        }
        order.reopenAfterChange();
        touch(order);
        return toDto(order);
    }

    @Override
    public RestaurantOrderDto updateLine(String uid, String lineUid, UpdateRestaurantOrderLineRequest req) {
        var order = lockLive(uid);
        var line = requireLine(order, lineUid);
        if (!line.isPending()) {
            throw new IllegalStateException(
                    "This item has already been sent. Void it instead, then add the right one.");
        }
        line.changePending(req.quantity(), blankToNull(req.note()), scope.actorId(), Instant.now());
        order.reopenAfterChange();
        touch(order);
        return toDto(order);
    }

    @Override
    public RestaurantOrderDto deleteLine(String uid, String lineUid) {
        var order = lockLive(uid);
        var line = requireLine(order, lineUid);
        if (!line.isPending()) {
            throw new IllegalStateException(
                    "This item has already been sent to the kitchen or bar. Void it instead.");
        }
        lines.delete(line);
        order.reopenAfterChange();
        touch(order);
        return toDto(order);
    }

    // -------------------------------------------------------------------------
    // Send (D-6)
    // -------------------------------------------------------------------------

    @Override
    public RestaurantSendResultDto send(String uid) {
        var order = lockLive(uid);
        var pending = lines.findByRestaurantOrderIdOrderByLineNoAsc(order.getId()).stream()
                .filter(RestaurantOrderLine::isPending)
                .toList();
        if (pending.isEmpty()) {
            return new RestaurantSendResultDto(toDto(order), List.of());
        }

        short round = (short) (lines.findMaxFireRound(order.getId()) + 1);
        Router router = router(order.getCompanyId(), order.getBranchId());
        Map<Long, Product> productCache = new HashMap<>();
        Long actor = scope.actorId();
        Instant now = Instant.now();
        for (var line : pending) {
            Product p = product(order.getCompanyId(), line.getProductId(), productCache);
            line.markSent(round, router.stationFor(p == null ? null : p.getCategory()), actor, now);
        }
        touch(order);

        List<PrepSlipDto> slips = slipsFor(PrepSlipKind.FIRE, order, pending, round, false,
                nameOf(actor), now, productCache);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_SEND, "restaurant_orders",
                order.getId(), order.getUid())
                .detail(Map.of("round", String.valueOf(round),
                        "items", String.valueOf(pending.size()),
                        "slips", String.valueOf(slips.size()))));
        return new RestaurantSendResultDto(toDto(order), slips);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrepSlipDto> slips(String uid, Integer round, String stationUid) {
        var order = orders.findByCompanyIdAndUid(scope.companyId(), uid)
                .orElseThrow(() -> NotFoundException.of("RestaurantOrder", uid));
        scope.assertCanActOn(order.getCompanyId(), order.getBranchId());
        int wanted = round != null ? round : lines.findMaxFireRound(order.getId());
        if (wanted <= 0) {
            return List.of();
        }
        Long stationId = null;
        if (stationUid != null && !stationUid.isBlank()) {
            stationId = stations.findByCompanyIdAndUid(order.getCompanyId(), stationUid)
                    .orElseThrow(() -> NotFoundException.of("PrepStation", stationUid))
                    .getId();
        }
        Long onlyStation = stationId;
        var sent = lines.findByRestaurantOrderIdOrderByLineNoAsc(order.getId()).stream()
                .filter(l -> l.isSent() && l.getFireRound() != null && l.getFireRound() == wanted)
                .filter(l -> onlyStation == null || onlyStation.equals(l.getPrepStationId()))
                .toList();
        if (sent.isEmpty()) {
            return List.of();
        }
        Long sender = sent.get(0).getSentBy();
        return slipsFor(PrepSlipKind.FIRE, order, sent, wanted, true, nameOf(sender),
                sent.get(0).getSentAt(), new HashMap<>());
    }

    // -------------------------------------------------------------------------
    // Void / cancel (D-9)
    // -------------------------------------------------------------------------

    @Override
    public RestaurantVoidResultDto voidLine(String uid, String lineUid, VoidRestaurantOrderLineRequest req) {
        var order = lockLive(uid);
        var line = requireLine(order, lineUid);
        if (line.isPending()) {
            throw new IllegalStateException(
                    "This item has not been sent yet. Remove it from the order instead.");
        }
        if (line.isVoided()) {
            throw new IllegalStateException("This item has already been voided.");
        }
        BigDecimal qty = req.quantity() != null ? req.quantity() : line.getQuantity();
        if (qty.compareTo(line.getQuantity()) > 0) {
            throw new IllegalArgumentException("You cannot void more than was ordered.");
        }
        Long authorisedBy = requireVoidAuthority(order, req.authorisedByUid(), lineUid);
        Long actor = scope.actorId();
        Instant now = Instant.now();
        String reason = req.reason().trim();

        RestaurantOrderLine voided;
        if (qty.compareTo(line.getQuantity()) == 0) {
            line.voidWhole(reason, actor, authorisedBy, now);
            voided = line;
        } else {
            voided = lines.save(line.splitVoided(lines.findMaxLineNo(order.getId()) + 1, qty,
                    reason, actor, authorisedBy, now));
        }
        order.reopenAfterChange();
        touch(order);

        List<PrepSlipDto> slips = slipsFor(PrepSlipKind.VOID, order, List.of(voided),
                voided.getFireRound(), false, nameOf(actor), now, new HashMap<>());
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_VOID, "restaurant_orders",
                order.getId(), order.getUid())
                .detail(voidDetail("VOIDED", voided.getUid(), qty, reason, authorisedBy)));
        return new RestaurantVoidResultDto(toDto(order), slips);
    }

    @Override
    public RestaurantVoidResultDto cancel(String uid, CancelRestaurantOrderRequest req) {
        var order = lockLive(uid);
        var sent = lines.findByRestaurantOrderIdOrderByLineNoAsc(order.getId()).stream()
                .filter(RestaurantOrderLine::isSent)
                .toList();
        String reason = req == null ? null : blankToNull(req.reason());
        Long authorisedBy = null;
        Long actor = scope.actorId();
        Instant now = Instant.now();
        if (!sent.isEmpty()) {
            if (reason == null) {
                throw new IllegalArgumentException(
                        "Some items were already sent. Give a reason for cancelling the order.");
            }
            authorisedBy = requireVoidAuthority(order, req.authorisedByUid(), null);
            for (var line : sent) {
                line.voidWhole(reason, actor, authorisedBy, now);
            }
        }
        order.cancel(reason, actor, now);
        touch(order);

        List<PrepSlipDto> slips = new ArrayList<>();
        Map<Integer, List<RestaurantOrderLine>> byRound = sent.stream()
                .collect(Collectors.groupingBy(l -> (int) l.getFireRound(), LinkedHashMap::new,
                        Collectors.toList()));
        Map<Long, Product> productCache = new HashMap<>();
        for (var e : byRound.entrySet()) {
            slips.addAll(slipsFor(PrepSlipKind.VOID, order, e.getValue(), e.getKey(), false,
                    nameOf(actor), now, productCache));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason == null ? "" : reason);
        detail.put("voidedItems", String.valueOf(sent.size()));
        detail.put("authorisedBy", authorisedBy == null ? "" : String.valueOf(authorisedBy));
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_CANCEL, "restaurant_orders",
                order.getId(), order.getUid()).detail(detail));
        return new RestaurantVoidResultDto(toDto(order), slips);
    }

    // -------------------------------------------------------------------------
    // Split / merge (ADR-0066 D-2, D-3)
    // -------------------------------------------------------------------------

    @Override
    public RestaurantSplitResultDto split(String uid, SplitRestaurantOrderRequest req) {
        var source = lockLive(uid);
        var all = lines.findByRestaurantOrderIdOrderByLineNoAsc(source.getId());
        Map<String, RestaurantOrderLine> byUid = all.stream()
                .collect(Collectors.toMap(RestaurantOrderLine::getUid, Function.identity()));

        // Validate the whole request before touching anything.
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        for (var item : req.items()) {
            var line = byUid.get(item.lineUid());
            if (line == null) {
                throw NotFoundException.of("RestaurantOrderLine", item.lineUid());
            }
            if (line.isVoided()) {
                throw new IllegalArgumentException("A voided item cannot be moved to another check.");
            }
            BigDecimal qty = item.quantity() != null ? item.quantity() : line.getQuantity();
            BigDecimal already = wanted.getOrDefault(line.getUid(), BigDecimal.ZERO);
            if (already.add(qty).compareTo(line.getQuantity()) > 0) {
                throw new IllegalArgumentException(
                        "You cannot move more " + productName(source, line) + " than was ordered.");
            }
            wanted.put(line.getUid(), already.add(qty));
        }
        BigDecimal left = all.stream().filter(l -> !l.isVoided())
                .map(l -> l.getQuantity().subtract(wanted.getOrDefault(l.getUid(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (left.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Nothing would be left on this check. Move the whole order to a table instead.");
        }

        Long actor = scope.actorId();
        Instant now = Instant.now();
        var created = new RestaurantOrder(source.getCompanyId(), source.getBranchId(),
                numberGen.nextRestaurantOrder(source.getCompanyId()), source.getOrderType(),
                source.getRestaurantTableId(), actor);
        created.setCustomerId(source.getCustomerId());
        created = orders.save(created);

        int lineNo = 0;
        for (var e : wanted.entrySet()) {
            var line = byUid.get(e.getKey());
            if (e.getValue().compareTo(line.getQuantity()) == 0) {
                line.moveTo(created, ++lineNo, actor, now);
            } else {
                lines.save(line.splitOff(created, ++lineNo, e.getValue(), actor, now));
            }
        }
        source.reopenAfterChange();
        touch(source);

        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_SPLIT, "restaurant_orders",
                source.getId(), source.getUid())
                .detail(Map.of("newOrder", created.getOrderNumber(),
                        "items", String.valueOf(wanted.size()))));
        return new RestaurantSplitResultDto(toDto(source), toDto(created));
    }

    @Override
    public RestaurantOrderDto merge(String uid, MergeRestaurantOrderRequest req) {
        if (uid.equals(req.fromOrderUid())) {
            throw new IllegalArgumentException("Choose a different order to merge.");
        }
        // Lock both in a fixed order, so two cashiers merging the same pair the opposite way round
        // wait for each other instead of deadlocking.
        boolean targetFirst = uid.compareTo(req.fromOrderUid()) < 0;
        RestaurantOrder target;
        RestaurantOrder source;
        if (targetFirst) {
            target = lockLive(uid);
            source = lockLive(req.fromOrderUid());
        } else {
            source = lockLive(req.fromOrderUid());
            target = lockLive(uid);
        }
        if (!source.getBranchId().equals(target.getBranchId())) {
            throw new IllegalArgumentException("Orders of different branches cannot be merged.");
        }

        Long actor = scope.actorId();
        Instant now = Instant.now();
        int lineNo = lines.findMaxLineNo(target.getId());
        var moving = lines.findByRestaurantOrderIdOrderByLineNoAsc(source.getId());
        for (var line : moving) {
            line.moveTo(target, ++lineNo, actor, now);
        }
        source.cancel("Merged into " + target.getOrderNumber(), actor, now);
        touch(source);
        target.reopenAfterChange();
        touch(target);

        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_MERGE, "restaurant_orders",
                target.getId(), target.getUid())
                .detail(Map.of("mergedFrom", source.getOrderNumber(),
                        "items", String.valueOf(moving.size()))));
        return toDto(target);
    }

    private String productName(RestaurantOrder order, RestaurantOrderLine line) {
        Product p = product(order.getCompanyId(), line.getProductId(), new HashMap<>());
        return p == null ? "of this item" : p.getName();
    }

    /**
     * The caller holds {@link #VOID_PERMISSION}, or a second person who holds it approved at the
     * till (step-up, re-verified here — never trusted from the request). Returns the approving
     * manager's id, or null when the caller acted on their own authority.
     */
    private Long requireVoidAuthority(RestaurantOrder order, String authorisedByUid, String lineUid) {
        RequestContext.Principal caller = RequestContext.get();
        if (permissionResolver.hasPermission(caller, VOID_PERMISSION, System.currentTimeMillis())) {
            return null;
        }
        var verdict = stepUpAuth.verifyAuthoriserUid(authorisedByUid, VOID_PERMISSION,
                order.getCompanyId());
        if (!verdict.authorised()) {
            // Independent: the refusal below rolls this transaction back, and "someone tried to
            // void a sent item without approval" is exactly what a shrinkage review looks for.
            audit.recordIndependent(AuditEvent.of(AuditActions.RESTAURANT_ORDER_VOID,
                    "restaurant_orders", order.getId(), order.getUid())
                    .detail(Map.of("outcome", "NOT_AUTHORISED",
                            "lineUid", lineUid == null ? "" : lineUid,
                            "authorisedByUid", authorisedByUid == null ? "" : authorisedByUid)));
            throw new ForbiddenException(
                    "Cancelling an item already sent to the kitchen or bar needs a manager's "
                            + "approval. Ask a manager to approve it at the till, then try again.");
        }
        return users.findByUid(verdict.authoriserUid()).map(AppUser::getId).orElse(null);
    }

    // -------------------------------------------------------------------------
    // Bill (D-7)
    // -------------------------------------------------------------------------

    @Override
    public RestaurantBillDto bill(String uid, BillRestaurantOrderRequest req) {
        var order = lockLive(uid);
        var active = activeLines(order);
        if (active.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item before printing the bill.");
        }
        Long customerId = req.customerId() != null ? req.customerId() : order.getCustomerId();
        if (req.customerId() != null) {
            requireCustomer(order.getCompanyId(), req.customerId());
            order.setCustomerId(req.customerId());
        }
        var priced = calculator.price(order.getCompanyId(), order.getBranchId(), customerId,
                req.currency(), combine(active));
        Instant now = Instant.now();
        order.markBilled(priced.gross(), now);
        touch(order);
        int unsent = (int) active.stream().filter(RestaurantOrderLine::isPending).count();

        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_BILL, "restaurant_orders",
                order.getId(), order.getUid())
                .detail(Map.of("grossTotal", priced.gross().toPlainString(),
                        "printCount", String.valueOf(order.getBillPrintCount()))));

        var billLines = priced.lines().stream()
                .map(l -> new RestaurantBillDto.Line(l.productName(), l.unitName(), l.quantity(),
                        l.unitGross(), l.gross()))
                .toList();
        return new RestaurantBillDto(order.getUid(), order.getOrderNumber(), order.getOrderType(),
                tableName(order), order.getGuestCount(), nameOf(order.getOpenedBy()), now,
                order.getBillPrintCount(), req.currency(), billLines,
                priced.net(), priced.vat(), priced.gross(), unsent);
    }

    // -------------------------------------------------------------------------
    // Pay (D-8)
    // -------------------------------------------------------------------------

    @Override
    public RestaurantPaymentDto pay(String uid, String idempotencyKey, PayRestaurantOrderRequest req) {
        var order = lock(uid);
        boolean idem = idempotencyKey != null && !idempotencyKey.isBlank();

        if (order.getStatus() == RestaurantOrderStatus.PAID) {
            // A retry of the payment that already settled this order is a success, not an error.
            if (idem) {
                var lookup = posSales.lookupByIdempotencyKey(idempotencyKey);
                if (lookup.verdict() == PosSaleLookupVerdict.POSTED
                        && Objects.equals(lookup.invoiceUid(), order.getSalesInvoiceUid())) {
                    return new RestaurantPaymentDto(toDto(order),
                            invoices.getByUid(order.getSalesInvoiceUid()));
                }
            }
            throw PosSaleFlowException.rejected("This order has already been paid.", "ALREADY_PAID");
        }
        if (order.getStatus() == RestaurantOrderStatus.CANCELLED) {
            throw PosSaleFlowException.rejected("This order was cancelled.", "ORDER_CANCELLED");
        }

        var session = sessions.findByUid(req.sessionUid())
                .orElseThrow(() -> NotFoundException.of("PosSession", req.sessionUid()));
        if (!session.getCompanyId().equals(order.getCompanyId())
                || !session.getBranchId().equals(order.getBranchId())) {
            throw PosSaleFlowException.rejected(
                    "This order belongs to another branch. Take payment on a till in that branch.",
                    "WRONG_BRANCH");
        }

        var active = activeLines(order);
        if (active.isEmpty()) {
            throw PosSaleFlowException.rejected("Add at least one item before taking payment.",
                    "ORDER_EMPTY");
        }
        Long customerId = req.customerId() != null ? req.customerId() : order.getCustomerId();
        if (customerId == null) {
            throw PosSaleFlowException.rejected("Choose a customer for this sale.", "CUSTOMER_REQUIRED");
        }
        requireCustomer(order.getCompanyId(), customerId);

        var items = combine(active);
        var priced = calculator.price(order.getCompanyId(), order.getBranchId(), customerId,
                req.currency(), items);
        BigDecimal expected = req.expectedTotal() != null
                ? req.expectedTotal()
                : order.getStatus() == RestaurantOrderStatus.BILLED ? order.getBilledTotalAmount() : null;
        if (expected != null && priced.gross().compareTo(expected) != 0) {
            throw priceChanged();
        }

        var sale = new PosSaleRequest(req.sessionUid(), customerId, null, req.currency(),
                items.stream()
                        .map(i -> new PosSaleRequest.LineItem(i.productId(), i.unitId(), i.quantity(),
                                null, null))
                        .toList(),
                req.tenders(), req.tenderedAmount(), saleNote(order), req.ageVerified(),
                req.belowCostApproved(), null);
        SalesInvoiceDto invoice = posSales.processSale(idempotencyKey, sale);

        // Belt and braces: the posted sale must be the figure the customer was shown. Throwing here
        // rolls the whole sale back — invoice, stock, ledger and the idempotency claim.
        if (invoice.grossTotalAmount().compareTo(priced.gross()) != 0) {
            throw priceChanged();
        }
        // A reused idempotency key would hand back ANOTHER order's sale.
        if (orders.existsBySalesInvoiceIdAndIdNot(invoice.id(), order.getId())) {
            throw PosSaleFlowException.rejected(
                    "This payment was already used for another order. Please try again.",
                    "DUPLICATE_PAYMENT");
        }

        order.setCustomerId(customerId);
        order.markPaid(invoice.id(), invoice.uid(), Instant.now());
        touch(order);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_ORDER_PAY, "restaurant_orders",
                order.getId(), order.getUid())
                .detail(Map.of("invoiceUid", invoice.uid(),
                        "grossTotal", invoice.grossTotalAmount().toPlainString())));
        return new RestaurantPaymentDto(toDto(order), invoice);
    }

    private static PosSaleFlowException priceChanged() {
        return PosSaleFlowException.rejected(
                "Prices have changed since the bill was printed. Print the bill again and show the "
                        + "customer the new total.",
                "PRICE_CHANGED");
    }

    private static String saleNote(RestaurantOrder order) {
        return order.getOrderType() == RestaurantOrderType.TAKEAWAY
                ? "Takeaway · " + order.getOrderNumber()
                : "Restaurant · " + order.getOrderNumber();
    }

    // -------------------------------------------------------------------------
    // Routing and slips
    // -------------------------------------------------------------------------

    /** Category → station for one branch, with the catch-all; null station = no stations at all. */
    private record Router(Map<String, Long> byCategory, Long fallback) {
        Long stationFor(String category) {
            String key = PrepStationCategory.key(category);
            Long routed = key == null ? null : byCategory.get(key);
            return routed != null ? routed : fallback;
        }
    }

    private Router router(Long companyId, Long branchId) {
        var active = stations.findByCompanyIdAndBranchIdAndStatus(companyId, branchId, MasterStatus.ACTIVE);
        Set<Long> activeIds = active.stream().map(PrepStation::getId).collect(Collectors.toSet());
        Map<String, Long> byCategory = new HashMap<>();
        for (var r : routes.findByCompanyIdAndBranchIdOrderByCategoryAsc(companyId, branchId)) {
            if (activeIds.contains(r.getPrepStationId())) {
                byCategory.put(PrepStationCategory.key(r.getCategory()), r.getPrepStationId());
            }
        }
        // The default station; if none is marked (e.g. it was deactivated), the first active one,
        // so an unrouted item is never silently left without a slip.
        Long fallback = active.stream().filter(PrepStation::isDefaultStation)
                .map(PrepStation::getId).findFirst()
                .orElseGet(() -> active.stream()
                        .min(Comparator.comparing(PrepStation::getName, String.CASE_INSENSITIVE_ORDER))
                        .map(PrepStation::getId).orElse(null));
        return new Router(byCategory, fallback);
    }

    /** One slip per station that has items in {@code group}; items without a station get none. */
    private List<PrepSlipDto> slipsFor(PrepSlipKind kind, RestaurantOrder order,
                                       List<RestaurantOrderLine> group, int round, boolean reprint,
                                       String byName, Instant at, Map<Long, Product> productCache) {
        Map<Long, List<RestaurantOrderLine>> byStation = new LinkedHashMap<>();
        for (var l : group) {
            if (l.getPrepStationId() != null) {
                byStation.computeIfAbsent(l.getPrepStationId(), k -> new ArrayList<>()).add(l);
            }
        }
        if (byStation.isEmpty()) {
            return List.of();
        }
        Map<Long, PrepStation> stationById = stations
                .findByCompanyIdAndIdIn(order.getCompanyId(), byStation.keySet()).stream()
                .collect(Collectors.toMap(PrepStation::getId, Function.identity()));
        Map<Long, UnitOfMeasure> unitCache = new HashMap<>();
        String tableName = tableName(order);
        List<PrepSlipDto> slips = new ArrayList<>();
        for (var e : byStation.entrySet()) {
            PrepStation s = stationById.get(e.getKey());
            if (s == null) {
                continue;
            }
            var slipLines = e.getValue().stream().map(l -> {
                Product p = product(order.getCompanyId(), l.getProductId(), productCache);
                UnitOfMeasure u = unit(order.getCompanyId(), l.getUnitId(), unitCache);
                return new PrepSlipDto.Line(p == null ? "Item" : p.getName(),
                        u == null ? null : u.getName(), l.getQuantity(), l.getNote(),
                        kind == PrepSlipKind.VOID ? l.getVoidReason() : null);
            }).toList();
            slips.add(new PrepSlipDto(kind, s.getUid(), s.getName(), s.getPrinterHost(),
                    s.getPrinterPort(), order.getOrderNumber(), order.getOrderType(), tableName,
                    order.getGuestCount(), round, reprint, byName, at, slipLines));
        }
        return slips;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Loads and locks the order (company-scoped), then proves the caller may act on its branch. */
    private RestaurantOrder lock(String uid) {
        var order = orders.findByCompanyIdAndUidForUpdate(scope.companyId(), uid)
                .orElseThrow(() -> NotFoundException.of("RestaurantOrder", uid));
        scope.assertCanActOn(order.getCompanyId(), order.getBranchId());
        return order;
    }

    /** {@link #lock} for a change: the order must still be on the floor. */
    private RestaurantOrder lockLive(String uid) {
        var order = lock(uid);
        if (order.getStatus() == RestaurantOrderStatus.PAID) {
            throw new IllegalStateException("This order has already been paid.");
        }
        if (order.getStatus() == RestaurantOrderStatus.CANCELLED) {
            throw new IllegalStateException("This order was cancelled.");
        }
        return order;
    }

    private RestaurantOrderLine requireLine(RestaurantOrder order, String lineUid) {
        return lines.findByRestaurantOrderIdAndUid(order.getId(), lineUid)
                .orElseThrow(() -> NotFoundException.of("RestaurantOrderLine", lineUid));
    }

    private RestaurantTable requireTable(Long companyId, Long branchId, String tableUid,
                                         boolean mustBeActive) {
        var table = tables.findByCompanyIdAndUid(companyId, tableUid)
                .orElseThrow(() -> NotFoundException.of("RestaurantTable", tableUid));
        if (!table.getBranchId().equals(branchId)) {
            throw new IllegalArgumentException("That table belongs to another branch.");
        }
        if (mustBeActive && table.getStatus() != MasterStatus.ACTIVE) {
            throw new IllegalArgumentException("Table " + table.getName() + " is not in use.");
        }
        return table;
    }

    private void requireCustomer(Long companyId, Long customerId) {
        customers.findByCompanyIdAndId(companyId, customerId)
                .orElseThrow(() -> new NotFoundException("This customer was not found."));
    }

    private List<RestaurantOrderLine> activeLines(RestaurantOrder order) {
        return lines.findByRestaurantOrderIdOrderByLineNoAsc(order.getId()).stream()
                .filter(l -> !l.isVoided())
                .toList();
    }

    /**
     * Combines identical items (same product, same unit) across rounds into one line, in first-seen
     * order. The bill and the sale use exactly this list, so they price the same lines.
     */
    private static List<RestaurantBillCalculator.Item> combine(List<RestaurantOrderLine> active) {
        Map<String, RestaurantBillCalculator.Item> byKey = new LinkedHashMap<>();
        for (var l : active) {
            String key = l.getProductId() + "|" + l.getUnitId();
            var prev = byKey.get(key);
            byKey.put(key, new RestaurantBillCalculator.Item(l.getProductId(), l.getUnitId(),
                    prev == null ? l.getQuantity() : prev.quantity().add(l.getQuantity())));
        }
        return new ArrayList<>(byKey.values());
    }

    private void touch(RestaurantOrder order) {
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(scope.actorId());
    }

    private Product product(Long companyId, Long id, Map<Long, Product> cache) {
        return cache.computeIfAbsent(id, k -> products.findByCompanyIdAndId(companyId, k).orElse(null));
    }

    private UnitOfMeasure unit(Long companyId, Long id, Map<Long, UnitOfMeasure> cache) {
        return cache.computeIfAbsent(id, k -> units.findByCompanyIdAndId(companyId, k).orElse(null));
    }

    private String tableName(RestaurantOrder order) {
        if (order.getRestaurantTableId() == null) {
            return null;
        }
        return tables.findByCompanyIdAndIdIn(order.getCompanyId(), List.of(order.getRestaurantTableId()))
                .stream().findFirst().map(RestaurantTable::getName).orElse(null);
    }

    private String nameOf(Long userId) {
        if (userId == null) {
            return UNNAMED;
        }
        return userLookup.displayNamesByIds(Set.of(userId)).getOrDefault(userId, UNNAMED);
    }

    private static Map<String, Object> voidDetail(String outcome, String lineUid, BigDecimal qty,
                                                  String reason, Long authorisedBy) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("outcome", outcome);
        d.put("lineUid", lineUid);
        d.put("quantity", qty.toPlainString());
        d.put("reason", reason);
        d.put("authorisedBy", authorisedBy == null ? "" : String.valueOf(authorisedBy));
        return d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private RestaurantOrderDto toDto(RestaurantOrder order) {
        return toDtos(List.of(order)).get(0);
    }

    /** Maps a page of orders with one read each for lines, tables, stations and names. */
    private List<RestaurantOrderDto> toDtos(List<RestaurantOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Long companyId = list.get(0).getCompanyId();
        var orderIds = list.stream().map(RestaurantOrder::getId).toList();
        Map<Long, List<RestaurantOrderLine>> linesByOrder = lines
                .findByRestaurantOrderIdInOrderByLineNoAsc(orderIds).stream()
                .collect(Collectors.groupingBy(RestaurantOrderLine::getRestaurantOrderId));

        Set<Long> tableIds = list.stream().map(RestaurantOrder::getRestaurantTableId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, RestaurantTable> tableById = tableIds.isEmpty() ? Map.of()
                : tables.findByCompanyIdAndIdIn(companyId, tableIds).stream()
                        .collect(Collectors.toMap(RestaurantTable::getId, Function.identity()));

        Set<Long> stationIds = linesByOrder.values().stream().flatMap(List::stream)
                .map(RestaurantOrderLine::getPrepStationId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PrepStation> stationById = stationIds.isEmpty() ? Map.of()
                : stations.findByCompanyIdAndIdIn(companyId, stationIds).stream()
                        .collect(Collectors.toMap(PrepStation::getId, Function.identity()));

        Set<Long> userIds = new HashSet<>();
        list.forEach(o -> userIds.add(o.getOpenedBy()));
        Map<Long, String> names = userLookup.displayNamesByIds(userIds);

        Map<Long, Product> productCache = new HashMap<>();
        Map<Long, UnitOfMeasure> unitCache = new HashMap<>();
        List<RestaurantOrderDto> out = new ArrayList<>(list.size());
        for (var o : list) {
            var orderLines = linesByOrder.getOrDefault(o.getId(), List.of());
            var lineDtos = orderLines.stream().map(l -> {
                Product p = product(companyId, l.getProductId(), productCache);
                UnitOfMeasure u = unit(companyId, l.getUnitId(), unitCache);
                PrepStation s = l.getPrepStationId() == null ? null : stationById.get(l.getPrepStationId());
                return new RestaurantOrderLineDto(l.getId(), l.getUid(), l.getLineNo(),
                        l.getProductId(), p == null ? null : p.getUid(),
                        p == null ? null : p.getCode(), p == null ? "Item" : p.getName(),
                        l.getUnitId(), u == null ? null : u.getName(),
                        l.getQuantity(), l.getNote(), l.getStatus(), l.getFireRound(),
                        s == null ? null : s.getUid(), s == null ? null : s.getName(),
                        l.getSentAt(), l.getVoidedAt(), l.getVoidReason());
            }).toList();
            int pending = (int) orderLines.stream().filter(RestaurantOrderLine::isPending).count();
            RestaurantTable t = o.getRestaurantTableId() == null ? null : tableById.get(o.getRestaurantTableId());
            out.add(new RestaurantOrderDto(o.getId(), o.getUid(), o.getBranchId(), o.getOrderNumber(),
                    o.getOrderType(), o.getStatus(),
                    t == null ? null : t.getUid(), t == null ? null : t.getName(),
                    o.getGuestCount(), o.getCustomerId(), o.getOpenedBy(),
                    names.getOrDefault(o.getOpenedBy(), UNNAMED), o.getOpenedAt(),
                    o.getBilledAt(), o.getBilledTotalAmount(), o.getBillPrintCount(),
                    o.getPaidAt(), o.getSalesInvoiceUid(), o.getCancelledAt(), o.getCancelReason(),
                    o.getNotes(), pending, lineDtos));
        }
        return out;
    }
}
