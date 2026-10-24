package com.erp.modules.sales.service;

import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.sales.domain.dto.KitchenTicketDto;
import com.erp.modules.sales.domain.entity.RestaurantOrder;
import com.erp.modules.sales.domain.entity.RestaurantOrderLine;
import com.erp.modules.sales.domain.entity.RestaurantTable;
import com.erp.modules.sales.domain.enums.RestaurantOrderLineStatus;
import com.erp.modules.sales.repository.PrepStationRepository;
import com.erp.modules.sales.repository.RestaurantOrderLineRepository;
import com.erp.modules.sales.repository.RestaurantOrderRepository;
import com.erp.modules.sales.repository.RestaurantTableRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
public class RestaurantKitchenServiceImpl implements RestaurantKitchenService {

    /** How far back the display looks: a line nobody bumped must not hang there forever. */
    static final Duration WINDOW = Duration.ofHours(24);

    private static final List<RestaurantOrderLineStatus> ON_DISPLAY =
            List.of(RestaurantOrderLineStatus.SENT, RestaurantOrderLineStatus.VOIDED);

    private final PrepStationRepository stations;
    private final RestaurantOrderLineRepository lines;
    private final RestaurantOrderRepository orders;
    private final RestaurantTableRepository tables;
    private final ProductRepository products;
    private final UserLookupService userLookup;
    private final RestaurantScope scope;
    private final AuditService audit;

    public RestaurantKitchenServiceImpl(PrepStationRepository stations,
                                        RestaurantOrderLineRepository lines,
                                        RestaurantOrderRepository orders,
                                        RestaurantTableRepository tables,
                                        ProductRepository products,
                                        UserLookupService userLookup,
                                        RestaurantScope scope,
                                        AuditService audit) {
        this.stations   = stations;
        this.lines      = lines;
        this.orders     = orders;
        this.tables     = tables;
        this.products   = products;
        this.userLookup = userLookup;
        this.scope      = scope;
        this.audit      = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KitchenTicketDto> tickets(String stationUid) {
        Long companyId = scope.companyId();
        var station = stations.findByCompanyIdAndUid(companyId, stationUid)
                .orElseThrow(() -> NotFoundException.of("PrepStation", stationUid));
        scope.assertCanActOn(station.getCompanyId(), station.getBranchId());

        var open = lines.findOpenForStation(companyId, station.getId(), ON_DISPLAY,
                Instant.now().minus(WINDOW));
        if (open.isEmpty()) {
            return List.of();
        }
        Map<Long, RestaurantOrder> orderById = orders
                .findByCompanyIdAndIdIn(companyId, open.stream()
                        .map(RestaurantOrderLine::getRestaurantOrderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RestaurantOrder::getId, Function.identity()));
        Set<Long> tableIds = orderById.values().stream().map(RestaurantOrder::getRestaurantTableId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> tableNames = tableIds.isEmpty() ? Map.of()
                : tables.findByCompanyIdAndIdIn(companyId, tableIds).stream()
                        .collect(Collectors.toMap(RestaurantTable::getId, RestaurantTable::getName));
        Set<Long> senders = new HashSet<>();
        open.forEach(l -> {
            if (l.getSentBy() != null) {
                senders.add(l.getSentBy());
            }
        });
        Map<Long, String> names = userLookup.displayNamesByIds(senders);
        Map<Long, Product> productCache = new HashMap<>();

        // One ticket per (order, round), oldest first.
        Map<String, List<RestaurantOrderLine>> byTicket = new LinkedHashMap<>();
        for (var l : open) {
            byTicket.computeIfAbsent(l.getRestaurantOrderId() + "#" + l.getFireRound(),
                    k -> new ArrayList<>()).add(l);
        }
        List<KitchenTicketDto> out = new ArrayList<>();
        for (var group : byTicket.values()) {
            var first = group.get(0);
            var order = orderById.get(first.getRestaurantOrderId());
            if (order == null) {
                continue;
            }
            Set<Long> ids = group.stream().map(RestaurantOrderLine::getId).collect(Collectors.toSet());
            Map<Long, List<RestaurantOrderLine>> addOns = new HashMap<>();
            List<RestaurantOrderLine> items = new ArrayList<>();
            for (var l : group) {
                if (l.isChild() && ids.contains(l.getParentLineId())) {
                    addOns.computeIfAbsent(l.getParentLineId(), k -> new ArrayList<>()).add(l);
                } else {
                    items.add(l);
                }
            }
            items.sort(java.util.Comparator
                    .comparing((RestaurantOrderLine l) -> l.getCourse() == null ? 1 : l.getCourse())
                    .thenComparing(RestaurantOrderLine::getLineNo));
            var ticketItems = items.stream().map(l -> new KitchenTicketDto.Item(
                    l.getUid(), name(companyId, l, productCache), l.getQuantity(), l.getNote(),
                    l.getCourse() == null ? 1 : l.getCourse(), l.isVoided(), l.getVoidReason(),
                    addOns.getOrDefault(l.getId(), List.of()).stream()
                            .map(c -> new KitchenTicketDto.AddOn(c.getUid(),
                                    name(companyId, c, productCache), c.getQuantity(), c.isVoided()))
                            .toList()))
                    .toList();
            out.add(new KitchenTicketDto(order.getUid(), order.getOrderNumber(), order.getOrderType(),
                    order.getRestaurantTableId() == null ? null : tableNames.get(order.getRestaurantTableId()),
                    order.getGuestCount(), first.getFireRound() == null ? 0 : first.getFireRound(),
                    first.getSentAt(),
                    first.getSentBy() == null ? null : names.getOrDefault(first.getSentBy(), "Staff"),
                    ticketItems));
        }
        out.sort(java.util.Comparator.comparing(KitchenTicketDto::sentAt));
        return out;
    }

    @Override
    public void ready(List<String> lineUids) {
        var targets = withAddOns(lineUids);
        Long actor = scope.actorId();
        Instant now = Instant.now();
        int marked = 0;
        for (var l : targets) {
            if (l.getReadyAt() == null && !l.isPending()) {
                l.markReady(actor, now);
                marked++;
            }
        }
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_KITCHEN_READY, "restaurant_order_lines")
                .detail(Map.of("items", String.valueOf(marked))));
    }

    @Override
    public void recall(List<String> lineUids) {
        var targets = withAddOns(lineUids);
        targets.forEach(RestaurantOrderLine::recall);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_KITCHEN_RECALL, "restaurant_order_lines")
                .detail(Map.of("items", String.valueOf(targets.size()))));
    }

    /** The named lines plus their add-ons, after proving the caller may act on each branch. */
    private List<RestaurantOrderLine> withAddOns(List<String> lineUids) {
        Long companyId = scope.companyId();
        var named = lines.findByCompanyIdAndUidIn(companyId, new HashSet<>(lineUids));
        if (named.size() != new HashSet<>(lineUids).size()) {
            throw new NotFoundException("One of the items was not found. Refresh the display.");
        }
        named.stream().map(RestaurantOrderLine::getBranchId).distinct()
                .forEach(b -> scope.assertCanActOn(companyId, b));
        List<RestaurantOrderLine> all = new ArrayList<>(named);
        all.addAll(lines.findByCompanyIdAndParentLineIdIn(companyId,
                named.stream().map(RestaurantOrderLine::getId).toList()));
        return all.stream().distinct().toList();
    }

    private String name(Long companyId, RestaurantOrderLine l, Map<Long, Product> cache) {
        Product p = cache.computeIfAbsent(l.getProductId(),
                id -> products.findByCompanyIdAndId(companyId, id).orElse(null));
        return p == null ? "Item" : p.getName();
    }
}
