package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterRowDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterTotalDto;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.BranchReadScope;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed Asset Register as at a date (FR-FA-17).
 *
 * <p>Figures are rolled back from the asset's stored running balances — the same columns the
 * FA-to-GL reconciliation sums — so on or after the latest posting the register agrees with the
 * reconciliation to the cent:
 * <ul>
 *   <li><b>cost</b> = {@code carrying_cost}, or, when a revaluation is dated after the as-at date,
 *       the earliest such revaluation's {@code carrying_before} (the cost before it happened);</li>
 *   <li><b>accumulated depreciation</b> = {@code accumulated_depreciation} less the posted schedule
 *       charges for periods after the as-at date (every posted charge adds exactly its
 *       {@code planned_charge} to the running balance — depreciation run and disposal catch-up);</li>
 *   <li><b>status as at</b>: DRAFT if never placed in service, or capitalised (capitalisation
 *       journal posting date) after the date; DISPOSED / WRITTEN_OFF from the disposal date; else
 *       IN_SERVICE.</li>
 * </ul>
 * Branch, location, category and cost centre are the asset's CURRENT values — transfers keep no
 * history, so a filter by branch is "assets now at this branch".
 *
 * <p>Cross-module reads (branches, dimension values, journal entries, the company currency) are
 * scalar SQL joins, never entity imports (module-boundary rule).
 */
@Component
@Transactional(readOnly = true)
public class FixedAssetRegisterQuery {

    private static final String DEFAULT_CURRENCY = "TZS";

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;
    private final CompanyCalendar calendar;

    public FixedAssetRegisterQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard, BranchReadGuard branchGuard, CompanyCalendar calendar) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
        this.calendar    = calendar;
    }

    public FixedAssetRegisterDto register(Long companyId, LocalDate asOf, String categoryUid,
                                          FixedAssetStatus status, String branchUid,
                                          String location, String costCentreUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        LocalDate date = asOf != null ? asOf : calendar.today(companyId);

        NamedRef category   = resolve("asset_categories", "name", categoryUid, companyId,
                "That asset category could not be found.");
        NamedRef branch     = resolve("branches", "name", branchUid, companyId,
                "That branch could not be found.");
        NamedRef costCentre = resolve("dimension_values", "name", costCentreUid, companyId,
                "That cost centre could not be found.");
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branch != null ? branch.id() : null);
        String locationFilter = location != null && !location.isBlank() ? location.trim() : null;

        List<Object> params = new ArrayList<>();
        params.add(date);            // revaluation roll-back
        params.add(date);            // posted-charge roll-back
        params.add(companyId);       // a.company_id
        params.add(date);            // acquisition_date <= ?
        StringBuilder filters = new StringBuilder(scope.sql("a.branch_id"));
        if (category != null) {
            filters.append(" AND a.category_id = ?");
            params.add(category.id());
        }
        if (branch != null) {
            filters.append(" AND a.branch_id = ?");
            params.add(branch.id());
        }
        if (costCentre != null) {
            filters.append(" AND a.cost_centre_id = ?");
            params.add(costCentre.id());
        }
        if (locationFilter != null) {
            filters.append(" AND a.location ILIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(locationFilter) + "%");
        }

        String sql = """
                SELECT a.uid, a.asset_number, a.name, a.status, a.disposed_at,
                       a.acquisition_date, a.acquisition_cost, a.location,
                       c.code AS category_code, c.name AS category_name,
                       b.name AS branch_name, dv.name AS cost_centre_name,
                       je.posting_date AS capitalised_on,
                       COALESCE((SELECT r.carrying_before
                                 FROM asset_revaluations r
                                 WHERE r.fixed_asset_id = a.id AND r.revaluation_date > ?
                                 ORDER BY r.revaluation_date, r.id
                                 LIMIT 1), a.carrying_cost) AS cost_as_of,
                       a.accumulated_depreciation
                         - COALESCE((SELECT SUM(s.planned_charge)
                                     FROM depreciation_schedule_lines s
                                     WHERE s.fixed_asset_id = a.id AND s.posted = true
                                       AND s.period_date > ?), 0) AS accum_as_of
                FROM fixed_assets a
                JOIN asset_categories c ON c.id = a.category_id
                LEFT JOIN branches b ON b.id = a.branch_id
                LEFT JOIN dimension_values dv ON dv.id = a.cost_centre_id
                LEFT JOIN journal_entries je ON je.uid = a.capitalised_gl_entry_uid
                                            AND je.company_id = a.company_id
                WHERE a.company_id = ?
                  AND a.acquisition_date <= ?
                """ + filters + """

                ORDER BY c.name, c.code, a.asset_number
                """;

        List<FixedAssetRegisterRowDto> all = jdbc.query(sql, (rs, n) -> {
            FixedAssetStatus current = FixedAssetStatus.valueOf(rs.getString("status"));
            LocalDate disposedAt     = rs.getObject("disposed_at", LocalDate.class);
            LocalDate capitalisedOn  = rs.getObject("capitalised_on", LocalDate.class);
            FixedAssetStatus asAt    = statusAsAt(current, capitalisedOn, disposedAt, date);
            BigDecimal cost          = rs.getBigDecimal("cost_as_of");
            boolean inService        = asAt == FixedAssetStatus.IN_SERVICE;
            // A draft asset has charged nothing; anything else carries what was posted by the date.
            BigDecimal accum = asAt == FixedAssetStatus.DRAFT
                    ? BigDecimal.ZERO : rs.getBigDecimal("accum_as_of");
            return new FixedAssetRegisterRowDto(
                    rs.getString("uid"),
                    rs.getString("asset_number"),
                    rs.getString("name"),
                    rs.getString("category_code"),
                    rs.getString("category_name"),
                    rs.getString("branch_name"),
                    rs.getString("location"),
                    rs.getString("cost_centre_name"),
                    rs.getObject("acquisition_date", LocalDate.class),
                    rs.getBigDecimal("acquisition_cost"),
                    cost,
                    accum,
                    inService ? cost.subtract(accum) : null,
                    asAt,
                    asAt == FixedAssetStatus.DISPOSED || asAt == FixedAssetStatus.WRITTEN_OFF
                            ? disposedAt : null,
                    inService);
        }, params.toArray());

        List<FixedAssetRegisterRowDto> rows = status == null ? all
                : all.stream().filter(r -> r.status() == status).toList();

        Map<String, Acc> byCategory = new LinkedHashMap<>();
        Acc grand = new Acc(null, null);
        int notInTotals = 0;
        for (FixedAssetRegisterRowDto r : rows) {
            if (!r.inTotals()) {
                notInTotals++;
                continue;
            }
            byCategory.computeIfAbsent(r.categoryCode(), k -> new Acc(r.categoryCode(), r.categoryName()))
                    .add(r);
            grand.add(r);
        }
        List<FixedAssetRegisterTotalDto> categoryTotals = byCategory.values().stream()
                .map(Acc::toDto).toList();

        return new FixedAssetRegisterDto(
                date.toString(),
                category != null ? category.name() : null,
                branch != null ? branch.name() : null,
                status,
                locationFilter,
                costCentre != null ? costCentre.name() : null,
                rows, categoryTotals, grand.toDto(), notInTotals,
                baseCurrency(companyId), Instant.now().toString());
    }

    /** Status the asset had on {@code date}, from today's status and the dates it changed. */
    static FixedAssetStatus statusAsAt(FixedAssetStatus current, LocalDate capitalisedOn,
                                       LocalDate disposedAt, LocalDate date) {
        if (current == FixedAssetStatus.DRAFT) {
            return FixedAssetStatus.DRAFT;
        }
        if (capitalisedOn != null && capitalisedOn.isAfter(date)) {
            return FixedAssetStatus.DRAFT;
        }
        if ((current == FixedAssetStatus.DISPOSED || current == FixedAssetStatus.WRITTEN_OFF)
                && disposedAt != null && disposedAt.isAfter(date)) {
            return FixedAssetStatus.IN_SERVICE;
        }
        return current;
    }

    // -------------------------------------------------------------------------

    private NamedRef resolve(String table, String nameColumn, String uid, Long companyId,
                             String notFoundMessage) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                "SELECT id, " + nameColumn + " AS name FROM " + table + " WHERE uid = ? AND company_id = ?",
                (rs, n) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            // Unknown or another company's uid: refuse rather than silently widen to everything.
            throw new NotFoundException(notFoundMessage);
        }
        return found.get(0);
    }

    private String baseCurrency(Long companyId) {
        List<String> found = jdbc.queryForList(
                "SELECT base_currency FROM companies WHERE id = ?", String.class, companyId);
        return found.isEmpty() || found.get(0) == null ? DEFAULT_CURRENCY : found.get(0);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record NamedRef(Long id, String name) {}

    private static final class Acc {
        private final String code;
        private final String name;
        private int count;
        private BigDecimal cost  = BigDecimal.ZERO;
        private BigDecimal accum = BigDecimal.ZERO;
        private BigDecimal nbv   = BigDecimal.ZERO;

        Acc(String code, String name) {
            this.code = code;
            this.name = name;
        }

        void add(FixedAssetRegisterRowDto r) {
            count++;
            cost  = cost.add(r.cost());
            accum = accum.add(r.accumulatedDepreciation());
            nbv   = nbv.add(r.nbv());
        }

        FixedAssetRegisterTotalDto toDto() {
            return new FixedAssetRegisterTotalDto(code, name, count, cost, accum, nbv);
        }
    }
}
