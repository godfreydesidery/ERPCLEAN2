package com.erp.modules.stock.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.StockAgeingReportDto;
import com.erp.modules.stock.domain.dto.StockAgeingRowDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock Ageing — how long the stock on the shelf has been there, per product, in five age buckets,
 * and how long since each product last sold (the slow- and dead-stock question).
 *
 * <p><b>The FIFO assumption.</b> Stock is not tagged with the day it arrived, so its age is
 * INFERRED: the units on hand are taken to be the most recently received ones (first in, first
 * out — the oldest sold first). Walking backwards from the as-of date through the inbound movements,
 * each one covers part of the on-hand until it is all accounted for; each part takes the age of the
 * movement that covered it. This is an ageing of QUANTITIES. The business costs stock at moving
 * average, not FIFO, so each bucket is valued at the product's single average cost — the buckets
 * then add up to exactly the value the Stock Valuation shows.
 *
 * <p><b>What counts as an arrival.</b> Positive goods receipts, opening balances, production
 * receipts and positive adjustments (including stock-count gains and bulk stock imports, which date
 * from the day they were posted). Transfers count only when they bring stock INTO the scope being
 * read: each transfer's legs are netted per product, so a move between two stores of one branch —
 * or, for the whole company, between any two branches — nets to nothing and leaves the stock's age
 * alone, while goods arriving from another branch are new to the branch they arrived in. Reversals
 * (a voided sale or a returned delivery putting stock back) are NOT arrivals: those are the original
 * units coming back, not new ones.
 *
 * <p><b>Uncovered stock.</b> When the recorded arrivals do not account for all of the on-hand —
 * stock that predates the movement history — the remainder's age is unknown. It is placed in the
 * oldest bucket, where it gets looked at, and flagged per row and counted, never silently aged.
 *
 * <p><b>As-of dates in the past.</b> Quantities are rebuilt as of that date (today's on-hand less
 * every movement since). The cost applied is still today's average, because a per-day cost history
 * is not kept; the response says so ({@code valuedAtCurrentCost}).
 *
 * <p>Gated {@code INVENTORY.VALUATION.VIEW} at the controller: it discloses stock value.
 */
@Component
@Transactional(readOnly = true)
public class StockAgeingReportQuery {

    private static final String CURRENCY_FALLBACK = "TZS";
    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    private static final int MONEY_SCALE = 2;

    /** Upper bound (inclusive, in days) of every bucket but the last, which is open-ended. */
    static final int[] BUCKET_UPPER_DAYS = {30, 60, 90, 180};
    static final List<String> BUCKET_LABELS = List.of(
            "0–30 days", "31–60 days", "61–90 days", "91–180 days", "Over 180 days");
    static final int BUCKETS = BUCKET_LABELS.size();

    /**
     * On-hand per product in scope, at the as-of date: today's on-hand less every movement posted
     * from the day after. Shared by both queries so they cannot disagree about how much there is.
     * Binds: companyId, branchId, branchId, companyId, branchId, branchId, asOfEnd.
     */
    private static final String ON_HAND_CTE = """
            soh AS (
                SELECT product_id,
                       SUM(quantity)      AS qty,
                       SUM(on_hand_value) AS val,
                       MAX(avg_cost)      AS stored_avg,
                       COUNT(*) FILTER (WHERE avg_cost IS NOT NULL) AS valued_rows
                FROM stock_on_hand
                WHERE company_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                GROUP BY product_id
            ),
            later AS (
                SELECT product_id, SUM(quantity) AS qty
                FROM stock_movements
                WHERE company_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                  AND occurred_at >= ?
                GROUP BY product_id
            )
            """;

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;

    public StockAgeingReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
                                  BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
    }

    /**
     * @param asOf      the date to age to; null means today (in the company's time zone)
     * @param branchUid optional; null ages the whole company's stock
     */
    public StockAgeingReportDto report(Long companyId, LocalDate asOf, String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        Company company = loadCompany(companyId);
        ZoneId zone = ZoneId.of(company.timeZone() != null ? company.timeZone() : DEFAULT_TIME_ZONE);
        LocalDate today = LocalDate.now(zone);
        // A future date has nothing to add — no movement is dated after now — so it ages to today.
        LocalDate asOfDate = asOf == null || asOf.isAfter(today) ? today : asOf;
        OffsetDateTime asOfEnd = asOfDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef branch = resolveBranch(branchUid, companyId);
        branchGuard.assertMayRead(principal, branch != null ? branch.id() : null);
        Long branchId = branch != null ? branch.id() : null;

        List<ProductOnHand> products = queryOnHand(companyId, branchId, asOfEnd);
        Map<Long, List<Layer>> layers = queryLayers(companyId, branchId, asOfEnd, zone);
        Map<Long, LocalDate> lastSale = queryLastSale(companyId, branchId, asOfEnd, zone);

        List<StockAgeingRowDto> rows = new ArrayList<>();
        int negative = 0;
        for (ProductOnHand p : products) {
            if (p.qtyAsOf().signum() < 0) {
                negative++;
                continue;
            }
            if (p.qtyAsOf().signum() == 0) {
                continue;
            }
            Allocation a = allocate(p.qtyAsOf(), layers.getOrDefault(p.id(), List.of()), asOfDate);

            BigDecimal unitCost = p.valued()
                    ? ProductStockReportQuery.buyingPrice(p.valueNow(), p.qtyNow(), p.storedAvg())
                    : null;
            BigDecimal value = null;
            List<BigDecimal> bucketValue = null;
            if (unitCost != null) {
                // Nothing moved after the as-of date: the stored value IS the value, to the cent.
                // Otherwise the as-of quantity at today's average.
                value = p.qtyAsOf().compareTo(p.qtyNow()) == 0 && p.valueNow() != null
                        ? p.valueNow().setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                        : p.qtyAsOf().multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                bucketValue = splitValue(a.bucketQty(), unitCost, value);
            }

            LocalDate sold = lastSale.get(p.id());
            rows.add(new StockAgeingRowDto(
                    p.uid(), p.code(), p.name(), p.unitName(),
                    p.qtyAsOf(), unitCost, value,
                    a.bucketQty(), bucketValue,
                    a.uncovered(),
                    sold != null ? sold.toString() : null,
                    sold != null ? ChronoUnit.DAYS.between(sold, asOfDate) : null));
        }

        return buildReport(company, asOfDate, today, branch, rows, negative);
    }

    private StockAgeingReportDto buildReport(Company company, LocalDate asOfDate, LocalDate today,
                                             NamedRef branch, List<StockAgeingRowDto> rows,
                                             int negative) {
        BigDecimal totalOnHand = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal[] bucketQty = zeros();
        BigDecimal[] bucketValue = zeros();
        int unvalued = 0;
        int uncovered = 0;
        int neverSold = 0;
        for (StockAgeingRowDto r : rows) {
            totalOnHand = totalOnHand.add(r.onHand());
            for (int i = 0; i < BUCKETS; i++) {
                bucketQty[i] = bucketQty[i].add(r.bucketQty().get(i));
            }
            if (r.value() != null) {
                totalValue = totalValue.add(r.value());
                for (int i = 0; i < BUCKETS; i++) {
                    bucketValue[i] = bucketValue[i].add(r.bucketValue().get(i));
                }
            } else {
                unvalued++;
            }
            if (r.uncoveredQty().signum() > 0) {
                uncovered++;
            }
            if (r.lastSaleDate() == null) {
                neverSold++;
            }
        }
        return new StockAgeingReportDto(
                company.header(),
                asOfDate.toString(),
                branch != null ? branch.name() : null,
                company.baseCurrency() != null ? company.baseCurrency() : CURRENCY_FALLBACK,
                BUCKET_LABELS,
                rows,
                totalOnHand,
                List.of(bucketQty),
                List.of(bucketValue),
                totalValue,
                unvalued,
                uncovered,
                negative,
                neverSold,
                asOfDate.isBefore(today),
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------
    // The arithmetic — static and package-private so it is testable without a database
    // -------------------------------------------------------------------------

    /** One arrival: the day it landed (company time zone) and how much it brought. */
    record Layer(LocalDate date, BigDecimal qty) {}

    /** The on-hand split into buckets, plus the part no arrival accounts for. */
    record Allocation(List<BigDecimal> bucketQty, BigDecimal uncovered) {}

    /**
     * Allocates {@code onHand} to the arrivals newest-first (FIFO: what is left is what came last).
     *
     * @param newestFirst arrivals ordered newest first
     */
    static Allocation allocate(BigDecimal onHand, List<Layer> newestFirst, LocalDate asOf) {
        BigDecimal[] buckets = zeros();
        BigDecimal remaining = onHand;
        for (Layer layer : newestFirst) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = layer.qty().min(remaining);
            if (take.signum() <= 0) {
                continue;
            }
            int b = bucketFor(ChronoUnit.DAYS.between(layer.date(), asOf));
            buckets[b] = buckets[b].add(take);
            remaining = remaining.subtract(take);
        }
        BigDecimal uncovered = remaining.signum() > 0 ? remaining : BigDecimal.ZERO;
        if (uncovered.signum() > 0) {
            // Unknown age goes where it will be questioned: the oldest bucket.
            buckets[BUCKETS - 1] = buckets[BUCKETS - 1].add(uncovered);
        }
        return new Allocation(List.of(buckets), uncovered);
    }

    static int bucketFor(long ageDays) {
        for (int i = 0; i < BUCKET_UPPER_DAYS.length; i++) {
            if (ageDays <= BUCKET_UPPER_DAYS[i]) {
                return i;
            }
        }
        return BUCKETS - 1;
    }

    /**
     * Values each bucket at the one average cost, rounded to the cent, and settles the rounding
     * difference on the last non-empty bucket so the buckets add up to {@code total} exactly.
     */
    static List<BigDecimal> splitValue(List<BigDecimal> bucketQty, BigDecimal unitCost,
                                       BigDecimal total) {
        BigDecimal[] out = zeros();
        int last = -1;
        BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (int i = 0; i < BUCKETS; i++) {
            BigDecimal q = bucketQty.get(i);
            out[i] = q.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            sum = sum.add(out[i]);
            if (q.signum() != 0) {
                last = i;
            }
        }
        if (last >= 0) {
            out[last] = out[last].add(total.subtract(sum));
        }
        return List.of(out);
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[BUCKETS];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    private record ProductOnHand(Long id, String uid, String code, String name, String unitName,
                                 BigDecimal qtyAsOf, BigDecimal qtyNow, BigDecimal valueNow,
                                 BigDecimal storedAvg, boolean valued) {}

    private List<Object> onHandParams(Long companyId, Long branchId, OffsetDateTime asOfEnd) {
        List<Object> p = new ArrayList<>();
        p.add(companyId);
        p.add(branchId);
        p.add(branchId);
        p.add(companyId);
        p.add(branchId);
        p.add(branchId);
        p.add(asOfEnd);
        return p;
    }

    private List<ProductOnHand> queryOnHand(Long companyId, Long branchId,
                                            OffsetDateTime asOfEnd) {
        List<Object> params = onHandParams(companyId, branchId, asOfEnd);
        params.add(companyId);
        String sql = "WITH " + ON_HAND_CTE + """
                SELECT p.id, p.uid, p.code, p.name, u.name AS unit_name,
                       soh.qty - COALESCE(later.qty, 0) AS qty_as_of,
                       soh.qty                          AS qty_now,
                       soh.val                          AS val,
                       soh.stored_avg                   AS stored_avg,
                       soh.valued_rows                  AS valued_rows
                FROM soh
                JOIN products p ON p.id = soh.product_id AND p.company_id = ?
                LEFT JOIN later ON later.product_id = soh.product_id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                ORDER BY p.code
                """;
        return jdbc.query(sql, (rs, rowNum) -> new ProductOnHand(
                rs.getLong("id"),
                rs.getString("uid"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("unit_name"),
                rs.getBigDecimal("qty_as_of"),
                rs.getBigDecimal("qty_now"),
                rs.getBigDecimal("val"),
                rs.getBigDecimal("stored_avg"),
                // Same guard as the stock registers: unvalued stock carries on_hand_value 0, and
                // dividing that out would print a confident 0.00 for stock nobody ever costed.
                rs.getInt("valued_rows") > 0), params.toArray());
    }

    /**
     * The arrivals that cover each product's on-hand, newest first — and only those: the window
     * sum stops returning rows once a product's on-hand is accounted for, so a long history is not
     * dragged across the wire to be discarded.
     */
    private Map<Long, List<Layer>> queryLayers(Long companyId, Long branchId,
                                               OffsetDateTime asOfEnd, ZoneId zone) {
        List<Object> params = new ArrayList<>(onHandParams(companyId, branchId, asOfEnd));
        params.add(companyId);
        params.add(branchId);
        params.add(branchId);
        params.add(asOfEnd);

        String sql = "WITH " + ON_HAND_CTE + """
                ,
                oh AS (
                    SELECT soh.product_id, soh.qty - COALESCE(later.qty, 0) AS qty
                    FROM soh LEFT JOIN later ON later.product_id = soh.product_id
                ),
                scoped AS (
                    SELECT id, uid, product_id, movement_type, quantity, occurred_at,
                           source_document_uid
                    FROM stock_movements
                    WHERE company_id = ?
                      AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                      AND occurred_at < ?
                ),
                inbound AS (
                    SELECT product_id, occurred_at, quantity, id AS ord
                    FROM scoped
                    WHERE quantity > 0
                      AND movement_type IN ('GOODS_RECEIPT', 'OPENING_BALANCE',
                                            'PRODUCTION_RECEIPT', 'ADJUSTMENT')
                    UNION ALL
                    -- A transfer's legs inside the scope, netted: only stock that ENTERED the
                    -- scope is an arrival, dated by its last inbound leg (the day it got here).
                    SELECT product_id,
                           MAX(occurred_at) FILTER (WHERE quantity > 0),
                           SUM(quantity),
                           MAX(id)
                    FROM scoped
                    WHERE movement_type IN ('TRANSFER_IN', 'TRANSFER_OUT')
                    GROUP BY product_id, COALESCE(source_document_uid, uid)
                    HAVING SUM(quantity) > 0
                ),
                ranked AS (
                    SELECT product_id, occurred_at, quantity, ord,
                           SUM(quantity) OVER (PARTITION BY product_id
                                               ORDER BY occurred_at DESC, ord DESC
                                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                               AS running
                    FROM inbound
                )
                SELECT r.product_id, r.occurred_at, r.quantity
                FROM ranked r
                JOIN oh ON oh.product_id = r.product_id
                WHERE oh.qty > 0
                  AND r.running - r.quantity < oh.qty
                ORDER BY r.product_id, r.occurred_at DESC, r.ord DESC
                """;

        Map<Long, List<Layer>> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            OffsetDateTime at = rs.getObject("occurred_at", OffsetDateTime.class);
            out.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
                    .add(new Layer(at.atZoneSameInstant(zone).toLocalDate(),
                            rs.getBigDecimal("quantity")));
        }, params.toArray());
        return out;
    }

    private Map<Long, LocalDate> queryLastSale(Long companyId, Long branchId,
                                               OffsetDateTime asOfEnd, ZoneId zone) {
        Map<Long, LocalDate> out = new HashMap<>();
        jdbc.query("""
                SELECT product_id, MAX(occurred_at) AS last_sale
                FROM stock_movements
                WHERE company_id = ?
                  AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                  AND movement_type = 'SALE_ISSUE'
                  AND occurred_at < ?
                GROUP BY product_id
                """, rs -> {
                    OffsetDateTime at = rs.getObject("last_sale", OffsetDateTime.class);
                    out.put(rs.getLong("product_id"), at.atZoneSameInstant(zone).toLocalDate());
                }, companyId, branchId, branchId, asOfEnd);
        return out;
    }

    private NamedRef resolveBranch(String uid, Long companyId) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of("Branch", uid);
        }
        return found.get(0);
    }

    private Company loadCompany(Long companyId) {
        List<Company> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new Company(
                        new ReportCompanyHeaderDto(
                                rs.getString("name"),
                                rs.getString("legal_name"),
                                rs.getString("address_line1"),
                                rs.getString("address_line2"),
                                rs.getString("city"),
                                rs.getString("region"),
                                rs.getString("country"),
                                rs.getString("contact_phone"),
                                rs.getString("contact_email"),
                                rs.getString("tax_id"),
                                rs.getString("vrn")),
                        rs.getString("time_zone"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    private record NamedRef(Long id, String name) {}

    private record Company(ReportCompanyHeaderDto header, String timeZone, String baseCurrency) {}
}
