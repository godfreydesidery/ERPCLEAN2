package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreatePrepStationRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantTableRequest;
import com.erp.modules.sales.domain.dto.PrepStationCategoryDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.ReplaceCategoryRoutesRequest;
import com.erp.modules.sales.domain.dto.RestaurantTableDto;
import com.erp.modules.sales.domain.dto.UpdatePrepStationRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantTableRequest;
import com.erp.modules.sales.domain.entity.PrepStation;
import com.erp.modules.sales.domain.entity.PrepStationCategory;
import com.erp.modules.sales.domain.entity.RestaurantOrder;
import com.erp.modules.sales.domain.entity.RestaurantTable;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.enums.RestaurantTableState;
import com.erp.modules.sales.repository.PrepStationCategoryRepository;
import com.erp.modules.sales.repository.PrepStationRepository;
import com.erp.modules.sales.repository.RestaurantOrderRepository;
import com.erp.modules.sales.repository.RestaurantTableRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RestaurantSetupServiceImpl implements RestaurantSetupService {

    private static final List<RestaurantOrderStatus> LIVE =
            List.of(RestaurantOrderStatus.OPEN, RestaurantOrderStatus.BILLED);

    private final RestaurantTableRepository tables;
    private final PrepStationRepository stations;
    private final PrepStationCategoryRepository routes;
    private final RestaurantOrderRepository orders;
    private final RestaurantScope scope;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public RestaurantSetupServiceImpl(RestaurantTableRepository tables,
                                      PrepStationRepository stations,
                                      PrepStationCategoryRepository routes,
                                      RestaurantOrderRepository orders,
                                      RestaurantScope scope,
                                      AuditService audit,
                                      JdbcTemplate jdbc) {
        this.tables   = tables;
        this.stations = stations;
        this.routes   = routes;
        this.orders   = orders;
        this.scope    = scope;
        this.audit    = audit;
        this.jdbc     = jdbc;
    }

    // -------------------------------------------------------------------------
    // Tables (D-3)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<RestaurantTableDto> listTables(String branchUid) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(branchUid);
        // Live state is derived, never stored: one read of the branch's live orders.
        Map<Long, List<RestaurantOrder>> liveByTable = orders
                .findByCompanyIdAndBranchIdAndStatusInOrderByOpenedAtAsc(companyId, branchId, LIVE)
                .stream()
                .filter(o -> o.getRestaurantTableId() != null)
                .collect(Collectors.groupingBy(RestaurantOrder::getRestaurantTableId));
        return tables.findByCompanyIdAndBranchIdOrderBySortOrderAscNameAsc(companyId, branchId)
                .stream()
                .map(t -> toDto(t, liveByTable.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Override
    public RestaurantTableDto createTable(CreateRestaurantTableRequest req) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(req.branchUid());
        String name = req.name().trim();
        if (tables.existsByBranchIdAndNameIgnoreCase(branchId, name)) {
            throw new ConflictException("This branch already has a table called \"" + name + "\".");
        }
        var t = new RestaurantTable(companyId, branchId, name, scope.actorId());
        if (req.seats() != null) {
            t.setSeats(req.seats());
        }
        t.setArea(blankToNull(req.area()));
        if (req.sortOrder() != null) {
            t.setSortOrder(req.sortOrder());
        }
        t = tables.save(t);
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_TABLE_CREATE, "restaurant_tables",
                t.getId(), t.getUid()).detail(Map.of("name", name)));
        return toDto(t, List.of());
    }

    @Override
    public RestaurantTableDto updateTable(String uid, UpdateRestaurantTableRequest req) {
        var t = tables.findByCompanyIdAndUid(scope.companyId(), uid)
                .orElseThrow(() -> NotFoundException.of("RestaurantTable", uid));
        scope.assertCanActOn(t.getCompanyId(), t.getBranchId());
        String name = req.name().trim();
        if (tables.existsByBranchIdAndNameIgnoreCaseAndIdNot(t.getBranchId(), name, t.getId())) {
            throw new ConflictException("This branch already has a table called \"" + name + "\".");
        }
        t.setName(name);
        if (req.seats() != null) {
            t.setSeats(req.seats());
        }
        t.setArea(blankToNull(req.area()));
        if (req.sortOrder() != null) {
            t.setSortOrder(req.sortOrder());
        }
        if (req.status() != null) {
            t.setStatus(req.status());
        }
        t.setUpdatedAt(Instant.now());
        t.setUpdatedBy(scope.actorId());
        audit.record(AuditEvent.of(AuditActions.RESTAURANT_TABLE_UPDATE, "restaurant_tables",
                t.getId(), t.getUid()).detail(Map.of("name", name, "status", t.getStatus().name())));
        var live = orders.findByCompanyIdAndBranchIdAndRestaurantTableIdAndStatusInOrderByOpenedAtAsc(
                t.getCompanyId(), t.getBranchId(), t.getId(), LIVE);
        return toDto(t, live);
    }

    // -------------------------------------------------------------------------
    // Prep stations (D-4)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PrepStationDto> listStations(String branchUid) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(branchUid);
        return stations.findByCompanyIdAndBranchIdOrderByNameAsc(companyId, branchId).stream()
                .map(RestaurantSetupServiceImpl::toDto)
                .toList();
    }

    @Override
    public PrepStationDto createStation(CreatePrepStationRequest req) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(req.branchUid());
        String name = req.name().trim();
        if (stations.existsByBranchIdAndNameIgnoreCase(branchId, name)) {
            throw new ConflictException("This branch already has a station called \"" + name + "\".");
        }
        var s = new PrepStation(companyId, branchId, name, scope.actorId());
        s.setPrinterHost(blankToNull(req.printerHost()));
        s.setPrinterPort(req.printerPort() != null ? req.printerPort() : PrepStation.DEFAULT_PRINTER_PORT);
        // The branch's first station is its default automatically: unrouted items must always have
        // somewhere to print, and a kitchen-only café should not need a second setup step.
        boolean noDefaultYet = stations.findByCompanyIdAndBranchIdAndStatus(
                        companyId, branchId, MasterStatus.ACTIVE).stream()
                .noneMatch(PrepStation::isDefaultStation);
        if (req.defaultStation() || noDefaultYet) {
            clearDefault(companyId, branchId, null);
            s.setDefaultStation(true);
        }
        s = stations.save(s);
        audit.record(AuditEvent.of(AuditActions.PREP_STATION_CREATE, "prep_stations",
                s.getId(), s.getUid()).detail(Map.of("name", name)));
        return toDto(s);
    }

    @Override
    public PrepStationDto updateStation(String uid, UpdatePrepStationRequest req) {
        var s = stations.findByCompanyIdAndUid(scope.companyId(), uid)
                .orElseThrow(() -> NotFoundException.of("PrepStation", uid));
        scope.assertCanActOn(s.getCompanyId(), s.getBranchId());
        String name = req.name().trim();
        if (stations.existsByBranchIdAndNameIgnoreCaseAndIdNot(s.getBranchId(), name, s.getId())) {
            throw new ConflictException("This branch already has a station called \"" + name + "\".");
        }
        s.setName(name);
        s.setPrinterHost(blankToNull(req.printerHost()));
        s.setPrinterPort(req.printerPort() != null ? req.printerPort() : PrepStation.DEFAULT_PRINTER_PORT);
        if (req.status() != null) {
            s.setStatus(req.status());
        }
        boolean makeDefault = req.defaultStation() && s.getStatus() == MasterStatus.ACTIVE;
        if (makeDefault && !s.isDefaultStation()) {
            clearDefault(s.getCompanyId(), s.getBranchId(), s.getId());
        }
        s.setDefaultStation(makeDefault);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(scope.actorId());
        audit.record(AuditEvent.of(AuditActions.PREP_STATION_UPDATE, "prep_stations",
                s.getId(), s.getUid()).detail(Map.of("name", name, "status", s.getStatus().name(),
                "default", String.valueOf(makeDefault))));
        return toDto(s);
    }

    /**
     * Takes the default flag off the branch's other stations, flushed BEFORE the new default is
     * written so the one-default partial unique index never sees two at once.
     */
    private void clearDefault(Long companyId, Long branchId, Long keepId) {
        boolean changed = false;
        for (var other : stations.findByCompanyIdAndBranchIdOrderByNameAsc(companyId, branchId)) {
            if (other.isDefaultStation() && !other.getId().equals(keepId)) {
                other.setDefaultStation(false);
                other.setUpdatedAt(Instant.now());
                other.setUpdatedBy(scope.actorId());
                changed = true;
            }
        }
        if (changed) {
            stations.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Category routing (D-4)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<PrepStationCategoryDto> listCategoryRoutes(String branchUid) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(branchUid);
        return toRouteDtos(companyId, branchId);
    }

    @Override
    public List<PrepStationCategoryDto> replaceCategoryRoutes(ReplaceCategoryRoutesRequest req) {
        Long companyId = scope.companyId();
        Long branchId = scope.branchId(req.branchUid());

        Map<String, PrepStation> stationByUid = stations
                .findByCompanyIdAndBranchIdOrderByNameAsc(companyId, branchId).stream()
                .collect(Collectors.toMap(PrepStation::getUid, Function.identity()));

        // Validate everything before deleting anything.
        Map<String, PrepStationCategory> byKey = new LinkedHashMap<>();
        for (var r : req.routes()) {
            String key = PrepStationCategory.key(r.category());
            if (key == null) {
                throw new IllegalArgumentException("A category cannot be blank.");
            }
            PrepStation station = stationByUid.get(r.prepStationUid());
            if (station == null) {
                throw new IllegalArgumentException(
                        "Choose a prep station of this branch for \"" + r.category().trim() + "\".");
            }
            if (byKey.containsKey(key)) {
                throw new IllegalArgumentException(
                        "\"" + r.category().trim() + "\" is listed twice. Route each category once.");
            }
            byKey.put(key, new PrepStationCategory(companyId, branchId, station.getId(),
                    r.category(), scope.actorId()));
        }

        routes.deleteByCompanyIdAndBranchId(companyId, branchId);
        routes.saveAll(byKey.values());
        audit.record(AuditEvent.of(AuditActions.PREP_ROUTING_REPLACE, "prep_station_categories")
                .detail(Map.of("branchId", String.valueOf(branchId),
                        "routes", String.valueOf(byKey.size()))));
        return toRouteDtos(companyId, branchId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> listProductCategories() {
        Long companyId = scope.companyId();
        List<String> raw = jdbc.queryForList(
                "SELECT DISTINCT category FROM products "
                        + "WHERE company_id = ? AND category IS NOT NULL AND btrim(category) <> ''",
                String.class, companyId);
        // One entry per category as the routing matches it: normalised, case-insensitive.
        Map<String, String> byKey = new HashMap<>();
        for (String c : raw) {
            String norm = PrepStationCategory.normalise(c);
            if (norm != null) {
                byKey.putIfAbsent(norm.toLowerCase(java.util.Locale.ROOT), norm);
            }
        }
        List<String> out = new ArrayList<>(byKey.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<PrepStationCategoryDto> toRouteDtos(Long companyId, Long branchId) {
        Map<Long, PrepStation> stationById = stations
                .findByCompanyIdAndBranchIdOrderByNameAsc(companyId, branchId).stream()
                .collect(Collectors.toMap(PrepStation::getId, Function.identity()));
        return routes.findByCompanyIdAndBranchIdOrderByCategoryAsc(companyId, branchId).stream()
                .map(r -> {
                    PrepStation s = stationById.get(r.getPrepStationId());
                    return new PrepStationCategoryDto(r.getUid(), r.getCategory(),
                            s == null ? null : s.getUid(), s == null ? null : s.getName());
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    static RestaurantTableDto toDto(RestaurantTable t, List<RestaurantOrder> live) {
        RestaurantTableState state = live.isEmpty()
                ? RestaurantTableState.FREE
                : live.stream().anyMatch(o -> o.getStatus() == RestaurantOrderStatus.BILLED)
                        ? RestaurantTableState.BILLED
                        : RestaurantTableState.OCCUPIED;
        return new RestaurantTableDto(t.getId(), t.getUid(), t.getBranchId(), t.getName(),
                t.getSeats(), t.getArea(), t.getSortOrder(), t.getStatus(), state, live.size());
    }

    static PrepStationDto toDto(PrepStation s) {
        return new PrepStationDto(s.getId(), s.getUid(), s.getBranchId(), s.getName(),
                s.getPrinterHost(), s.getPrinterPort(), s.isDefaultStation(), s.getStatus());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
