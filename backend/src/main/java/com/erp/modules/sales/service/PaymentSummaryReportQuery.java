package com.erp.modules.sales.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryReportDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryReportDto.CashierRefDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryRowDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryTotalsDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily cash-up / Payment Summary — the money taken for finalised sales, per day, cashier and
 * payment method (cash, mobile money, card, cheque).
 *
 * <p><b>Source.</b> {@code sales_invoice_payments}: every tender taken against a sales invoice,
 * whether rung at a POS till or settled at the counter. It carries the method, who took it
 * ({@code received_by}) and when ({@code received_at}). The day and the cashier are those of the
 * PAYMENT, not of the invoice — a cash-up is about when and by whom money changed hands.
 *
 * <p><b>Same rules as the till's X/Z-read</b> ({@code SalesInvoicePaymentRepository}): only
 * payments on invoices that are FINALISED now count (a voided sale's money was handed back), and
 * each tender is netted of the change returned ({@code amount − change_amount}).
 *
 * <p><b>Not included:</b> receipts against credit invoices recorded in Receivables
 * ({@code ar_receipts}), till payouts and expenses, and the opening float. This answers "what did
 * the sales bring in, by method"; the X/Z-read remains the drawer reconciliation for one session.
 *
 * <p>Amounts in different currencies are never summed together — totals come one per currency.
 */
@Component
@Transactional(readOnly = true)
public class PaymentSummaryReportQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;

    public PaymentSummaryReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
                                     BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
    }

    /**
     * @param branchUid  optional; null covers every branch
     * @param cashierUid optional; null covers every cashier
     */
    public PaymentSummaryReportDto report(Long companyId, LocalDate fromDate, LocalDate toDate,
                                          String branchUid, String cashierUid) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }

        CompanyHeader header = loadCompanyHeader(companyId);
        ZoneId zone = ZoneId.of(header.timeZone() != null ? header.timeZone() : DEFAULT_TIME_ZONE);
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to   = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef branch = resolveBranch(branchUid, companyId);
        branchGuard.assertMayRead(principal, branch != null ? branch.id() : null);
        NamedRef cashier = resolveCashier(cashierUid, companyId);

        String baseCurrency = header.baseCurrency();
        List<PaymentSummaryRowDto> rows =
                queryRows(zone, companyId, from, to, branch, cashier);

        return new PaymentSummaryReportDto(
                header.toDto(),
                fromDate.toString(),
                toDate.toString(),
                branch != null ? branch.name() : null,
                cashier != null ? cashier.name() : null,
                baseCurrency,
                rows,
                totalsOf(rows, baseCurrency),
                queryCashiers(companyId, from, to, branch),
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private List<PaymentSummaryRowDto> queryRows(ZoneId zone, Long companyId, OffsetDateTime from,
                                                 OffsetDateTime to, NamedRef branch,
                                                 NamedRef cashier) {
        List<Object> params = new ArrayList<>();
        params.add(zone.getId());   // the day expression is the first select item
        params.add(companyId);
        params.add(companyId);
        params.add(from);
        params.add(to);
        StringBuilder filter = new StringBuilder();
        if (branch != null) {
            filter.append(" AND p.branch_id = ?");
            params.add(branch.id());
        }
        if (cashier != null) {
            filter.append(" AND p.received_by = ?");
            params.add(cashier.id());
        }

        String sql = """
                SELECT to_char(p.received_at AT TIME ZONE ?, 'YYYY-MM-DD') AS day,
                       u.uid                                              AS cashier_uid,
                       u.display_name                                     AS cashier_name,
                       p.currency                                         AS currency,
                       p.tender_type                                      AS tender,
                       COUNT(*)                                           AS payments,
                       SUM(p.amount - COALESCE(p.change_amount, 0))       AS amount
                FROM sales_invoice_payments p
                JOIN sales_invoices i ON i.id = p.invoice_id
                LEFT JOIN app_users u ON u.id = p.received_by
                WHERE p.company_id = ?
                  AND i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND p.received_at >= ?
                  AND p.received_at <  ?
                """ + filter + """

                GROUP BY 1, 2, 3, 4, 5
                ORDER BY 1, 3 NULLS LAST, 2, 4, 5
                """;

        // One line per (day, cashier, currency); the tender types become its columns. The query
        // is ordered on exactly that key, so a LinkedHashMap keeps the printed order.
        Map<String, Acc> lines = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String day = rs.getString("day");
            String cashierUid = rs.getString("cashier_uid");
            String cashierName = nameOrUnknown(rs.getString("cashier_name"));
            String currency = rs.getString("currency");
            String key = day + '|' + Objects.toString(cashierUid, "") + '|' + currency;
            lines.computeIfAbsent(key, k -> new Acc(day, cashierUid, cashierName, currency))
                    .add(rs.getString("tender"), rs.getBigDecimal("amount"),
                            rs.getLong("payments"));
        }, params.toArray());

        return lines.values().stream().map(Acc::toRow).toList();
    }

    /** Everyone who took a payment in the window (and branch), whatever the cashier filter says. */
    private List<CashierRefDto> queryCashiers(Long companyId, OffsetDateTime from,
                                              OffsetDateTime to, NamedRef branch) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        params.add(companyId);
        params.add(from);
        params.add(to);
        String branchSql = "";
        if (branch != null) {
            branchSql = " AND p.branch_id = ?";
            params.add(branch.id());
        }
        String sql = """
                SELECT DISTINCT u.uid AS uid, u.display_name AS name
                FROM sales_invoice_payments p
                JOIN sales_invoices i ON i.id = p.invoice_id
                JOIN app_users u ON u.id = p.received_by
                WHERE p.company_id = ?
                  AND i.company_id = ?
                  AND i.status = 'FINALISED'
                  AND p.received_at >= ?
                  AND p.received_at <  ?
                """ + branchSql + """

                ORDER BY 2, 1
                """;
        return jdbc.query(sql,
                (rs, rowNum) -> new CashierRefDto(rs.getString("uid"), rs.getString("name")),
                params.toArray());
    }

    /** Accumulates one printed line. */
    private static final class Acc {
        private final String date;
        private final String cashierUid;
        private final String cashierName;
        private final String currency;
        private BigDecimal cash = BigDecimal.ZERO;
        private BigDecimal mobile = BigDecimal.ZERO;
        private BigDecimal card = BigDecimal.ZERO;
        private BigDecimal cheque = BigDecimal.ZERO;
        private long payments;

        Acc(String date, String cashierUid, String cashierName, String currency) {
            this.date = date;
            this.cashierUid = cashierUid;
            this.cashierName = cashierName;
            this.currency = currency;
        }

        void add(String tender, BigDecimal amount, long count) {
            BigDecimal a = amount != null ? amount : BigDecimal.ZERO;
            switch (tender) {
                case "CASH" -> cash = cash.add(a);
                case "MOBILE_MONEY" -> mobile = mobile.add(a);
                case "CARD" -> card = card.add(a);
                case "CHEQUE" -> cheque = cheque.add(a);
                // The column CHECK admits only the four above; anything else would be a schema
                // change this report has not been taught about, so fail loudly, not silently.
                default -> throw new IllegalStateException("Unrecognised payment method.");
            }
            payments += count;
        }

        PaymentSummaryRowDto toRow() {
            return new PaymentSummaryRowDto(date, cashierUid, cashierName, currency,
                    cash, mobile, card, cheque, cash.add(mobile).add(card).add(cheque), payments);
        }
    }

    /**
     * Column totals, one per currency, base currency first.
     *
     * <p>Package-private and static so "the totals are the sum of the lines" — the identity a
     * cash-up is checked against — is testable without a database.
     */
    static List<PaymentSummaryTotalsDto> totalsOf(List<PaymentSummaryRowDto> rows,
                                                  String baseCurrency) {
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        if (baseCurrency != null) {
            // Base currency always prints first — and prints at all, even on an empty day, so a
            // blank cash-up reads as "nothing taken" rather than as a broken report.
            sums.put(baseCurrency, zeros());
            counts.put(baseCurrency, 0L);
        }
        for (PaymentSummaryRowDto r : rows) {
            BigDecimal[] s = sums.computeIfAbsent(r.currency(), c -> zeros());
            s[0] = s[0].add(r.cash());
            s[1] = s[1].add(r.mobileMoney());
            s[2] = s[2].add(r.card());
            s[3] = s[3].add(r.cheque());
            counts.merge(r.currency(), r.payments(), Long::sum);
        }
        List<PaymentSummaryTotalsDto> out = new ArrayList<>();
        sums.forEach((currency, s) -> out.add(new PaymentSummaryTotalsDto(currency,
                s[0], s[1], s[2], s[3], s[0].add(s[1]).add(s[2]).add(s[3]),
                counts.getOrDefault(currency, 0L))));
        return out;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static String nameOrUnknown(String name) {
        return name != null ? name : "(not recorded)";
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

    /**
     * Resolves a cashier filter WITHIN the caller's company: the user must be (or have been) a
     * member of it, or have taken a payment in it. Users are organisation-level rows, so a bare uid
     * lookup would accept a user from another company and quietly answer with nothing — an unknown
     * cashier is refused instead, the same as an unknown branch.
     */
    private NamedRef resolveCashier(String uid, Long companyId) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                """
                SELECT u.id, u.display_name AS name
                FROM app_users u
                WHERE u.uid = ?
                  AND (EXISTS (SELECT 1 FROM user_company uc
                               WHERE uc.user_id = u.id AND uc.company_id = ?)
                       OR EXISTS (SELECT 1 FROM sales_invoice_payments p
                                  WHERE p.received_by = u.id AND p.company_id = ?))
                """,
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of("Cashier", uid);
        }
        return found.get(0);
    }

    private CompanyHeader loadCompanyHeader(Long companyId) {
        List<CompanyHeader> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new CompanyHeader(
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

    private record CompanyHeader(ReportCompanyHeaderDto toDto, String timeZone,
                                 String baseCurrency) {}
}
