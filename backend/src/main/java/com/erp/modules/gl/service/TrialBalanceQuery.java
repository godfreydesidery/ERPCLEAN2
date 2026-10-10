package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRangeDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRowDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import java.time.LocalDate;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the trial balance on demand (ADR-0013 D-8, FR-GL-16).
 * Not stored — computed from journal_lines GROUP BY account_id.
 * A correct set of books yields totalDebits == totalCredits (nets to zero).
 *
 * <p>The DTO also carries the printable letterhead (company block, base currency, period label,
 * generated-at) so the PDF/Excel/CSV export has a page head without a second round trip. The
 * letterhead is read with {@link JdbcTemplate} rather than a company repository: {@code companies}
 * belongs to the IAM module and GL may not import another module's entities or repositories
 * (ModuleBoundaryTest) — the same route {@code StockReportQuery} and {@code SalesReportQuery} take.
 */
@Component
public class TrialBalanceQuery {

    /** Used only when the company row carries no base currency of its own. */
    private static final String CURRENCY_FALLBACK = "TZS";

    /** ASCII only: these strings reach the PDF, whose Helvetica default encoding drops U+2212 etc. */
    private static final String ALL_PERIODS_LABEL = "All periods";

    private final JournalLineRepository lineRepo;
    private final ChartOfAccountRepository accountRepo;
    private final ScopeGuard scopeGuard;
    private final JdbcTemplate jdbc;
    private final CompanyCalendar calendar;

    public TrialBalanceQuery(JournalLineRepository lineRepo,
                              ChartOfAccountRepository accountRepo,
                              ScopeGuard scopeGuard,
                              JdbcTemplate jdbc,
                              CompanyCalendar calendar) {
        this.calendar    = calendar;
        this.lineRepo    = lineRepo;
        this.accountRepo = accountRepo;
        this.scopeGuard  = scopeGuard;
        this.jdbc        = jdbc;
    }

    /** Full trial balance for a company (all periods). */
    @Transactional(readOnly = true)
    public TrialBalanceDto compute(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> sums = lineRepo.trialBalanceSums(companyId);
        return buildDto(companyId, sums, ALL_PERIODS_LABEL);
    }

    /** Trial balance filtered to a single fiscal period. */
    @Transactional(readOnly = true)
    public TrialBalanceDto computeForPeriod(Long companyId, Long periodId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> sums = lineRepo.trialBalanceSumsByPeriod(companyId, periodId);
        return buildDto(companyId, sums, periodLabel(companyId, periodId));
    }

    /**
     * Trial balance as at {@code asAt}, with opening / movement / closing per account (ACC-14).
     *
     * @param from      first day of the movement window; null = no opening (all history is movement)
     * @param asAt      last day included; null = today
     * @param branchUid limit to one branch of the company (resolved company-scoped); null = all.
     *                  Company-level journals (no branch) are then excluded.
     */
    @Transactional(readOnly = true)
    public TrialBalanceRangeDto computeRange(Long companyId, LocalDate from, LocalDate asAt,
                                             String branchUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        LocalDate end = asAt != null ? asAt : calendar.today(companyId);
        if (from != null && from.isAfter(end)) {
            throw new IllegalArgumentException("The start date must not be after the as-at date.");
        }

        Long branchId = null;
        String branchName = null;
        if (branchUid != null && !branchUid.isBlank()) {
            List<Object[]> found = jdbc.query(
                    "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                    (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("name")},
                    branchUid.trim(), companyId);
            if (found.isEmpty()) {
                throw new NotFoundException("Branch not found.");
            }
            branchId = (Long) found.get(0)[0];
            branchName = (String) found.get(0)[1];
        }

        // Opening = before `from`; movement = from..asAt. With no `from`, everything is movement.
        LocalDate movementStart = from != null ? from : LocalDate.of(1, 1, 1);
        StringBuilder sql = new StringBuilder("""
                SELECT l.account_id,
                       SUM(CASE WHEN e.posting_date <  ? THEN l.debit_amount  ELSE 0 END) AS open_dr,
                       SUM(CASE WHEN e.posting_date <  ? THEN l.credit_amount ELSE 0 END) AS open_cr,
                       SUM(CASE WHEN e.posting_date >= ? THEN l.debit_amount  ELSE 0 END) AS mov_dr,
                       SUM(CASE WHEN e.posting_date >= ? THEN l.credit_amount ELSE 0 END) AS mov_cr
                FROM journal_lines l
                JOIN journal_entries e ON e.id = l.entry_id
                WHERE l.company_id = ?
                  AND e.posting_date <= ?
                """);
        List<Object> args = new ArrayList<>(List.of(java.sql.Date.valueOf(movementStart),
                java.sql.Date.valueOf(movementStart), java.sql.Date.valueOf(movementStart),
                java.sql.Date.valueOf(movementStart), companyId, java.sql.Date.valueOf(end)));
        if (branchId != null) {
            sql.append("  AND l.branch_id = ?\n");
            args.add(branchId);
        }
        sql.append("GROUP BY l.account_id");
        List<Object[]> sums = jdbc.query(sql.toString(),
                (rs, n) -> new Object[]{rs.getLong("account_id"), rs.getBigDecimal("open_dr"),
                        rs.getBigDecimal("open_cr"), rs.getBigDecimal("mov_dr"),
                        rs.getBigDecimal("mov_cr")},
                args.toArray());

        Map<Long, ChartOfAccount> accountMap = new HashMap<>();
        accountRepo.findByCompanyId(companyId,
                org.springframework.data.domain.Pageable.unpaged()).forEach(
                a -> accountMap.put(a.getId(), a));

        List<TrialBalanceRangeDto.Row> rows = new ArrayList<>();
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO};
        for (Object[] s : sums) {
            ChartOfAccount acct = accountMap.get((Long) s[0]);
            if (acct == null) {
                continue;
            }
            BigDecimal openNet = toBD(s[1]).subtract(toBD(s[2]));
            BigDecimal movDr = toBD(s[3]);
            BigDecimal movCr = toBD(s[4]);
            BigDecimal closeNet = openNet.add(movDr).subtract(movCr);
            if (openNet.signum() == 0 && movDr.signum() == 0 && movCr.signum() == 0) {
                continue;
            }
            TrialBalanceRangeDto.Row row = new TrialBalanceRangeDto.Row(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    acct.getAccountType(), acct.getNormalBalance(),
                    debitSide(openNet), creditSide(openNet), movDr, movCr,
                    debitSide(closeNet), creditSide(closeNet));
            rows.add(row);
            t[0] = t[0].add(row.openingDebit());
            t[1] = t[1].add(row.openingCredit());
            t[2] = t[2].add(movDr);
            t[3] = t[3].add(movCr);
            t[4] = t[4].add(row.closingDebit());
            t[5] = t[5].add(row.closingCredit());
        }
        rows.sort((a, b) -> a.accountCode().compareTo(b.accountCode()));

        CompanyHeader header = loadCompanyHeader(companyId);
        ReportCompanyHeaderDto company = header == null ? null : new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());
        String currency = header != null && header.baseCurrency() != null
                ? header.baseCurrency() : CURRENCY_FALLBACK;
        String label = (from != null ? from + " to " + end : "As at " + end)
                + (branchName != null ? " - branch " + branchName : "");

        return new TrialBalanceRangeDto(companyId, company, currency, from, end,
                branchId != null ? branchUid.trim() : null, branchName, label, rows,
                t[0], t[1], t[2], t[3], t[4], t[5], Instant.now().toString());
    }

    /**
     * The closing balances of a range trial balance in the classic two-column shape, so the
     * existing PDF/Excel/CSV export prints an "as at" trial balance unchanged.
     */
    public static TrialBalanceDto closingAsTotals(TrialBalanceRangeDto r) {
        List<TrialBalanceRowDto> rows = r.rows().stream()
                .map(x -> new TrialBalanceRowDto(x.accountId(), x.accountUid(), x.accountCode(),
                        x.accountName(), x.accountType(), x.normalBalance(),
                        x.closingDebit(), x.closingCredit(),
                        x.closingDebit().subtract(x.closingCredit())))
                .toList();
        return new TrialBalanceDto(r.companyId(), r.company(), r.baseCurrency(), r.periodLabel(),
                rows, r.closingDebit(), r.closingCredit(), r.generatedAt());
    }

    private static BigDecimal debitSide(BigDecimal net) {
        return net.signum() > 0 ? net : BigDecimal.ZERO;
    }

    private static BigDecimal creditSide(BigDecimal net) {
        return net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------

    private TrialBalanceDto buildDto(Long companyId, List<Object[]> sums, String periodLabel) {
        // Pre-fetch accounts for this company to enrich rows without N+1 queries
        Map<Long, ChartOfAccount> accountMap = new HashMap<>();
        accountRepo.findByCompanyId(companyId,
                org.springframework.data.domain.Pageable.unpaged()).forEach(
                a -> accountMap.put(a.getId(), a));

        List<TrialBalanceRowDto> rows = new ArrayList<>();
        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Object[] row : sums) {
            Long       accountId = (Long)       row[0];
            BigDecimal debit     = toBD(row[1]);
            BigDecimal credit    = toBD(row[2]);

            ChartOfAccount acct = accountMap.get(accountId);
            if (acct == null) {
                // account was deleted after posting — skip (historical orphan)
                continue;
            }
            BigDecimal net = debit.subtract(credit);
            rows.add(new TrialBalanceRowDto(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    acct.getAccountType(), acct.getNormalBalance(),
                    debit, credit, net));
            totalDebit  = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        // Sort by account code
        rows.sort((a, b) -> a.accountCode().compareTo(b.accountCode()));

        CompanyHeader header = loadCompanyHeader(companyId);
        ReportCompanyHeaderDto company = header == null ? null : new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());
        String currency = header != null && header.baseCurrency() != null
                ? header.baseCurrency()
                : CURRENCY_FALLBACK;

        return new TrialBalanceDto(companyId, company, currency, periodLabel,
                rows, totalDebit, totalCredit, Instant.now().toString());
    }

    /**
     * "Period 3: 2026-03-01 to 2026-03-31" — the reader of a printed trial balance has to be able to
     * tell which period it covers without asking. ASCII only ("to", never an en dash).
     */
    private String periodLabel(Long companyId, Long periodId) {
        List<String> found = jdbc.query(
                """
                SELECT period_no, start_date, end_date
                FROM fiscal_periods
                WHERE id = ? AND company_id = ?
                """,
                (rs, rowNum) -> "Period " + rs.getInt("period_no") + ": "
                        + rs.getDate("start_date").toLocalDate() + " to "
                        + rs.getDate("end_date").toLocalDate(),
                periodId, companyId);
        return found.isEmpty() ? "Selected period" : found.get(0);
    }

    /**
     * The letterhead block. Deliberately lenient: an unreadable company row yields null and the
     * export prints no letterhead rather than failing — the figures are what the accountant came for.
     */
    private CompanyHeader loadCompanyHeader(Long companyId) {
        List<CompanyHeader> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new CompanyHeader(
                        rs.getString("name"),
                        rs.getString("legal_name"),
                        rs.getString("tax_id"),
                        rs.getString("vrn"),
                        rs.getString("contact_phone"),
                        rs.getString("contact_email"),
                        rs.getString("address_line1"),
                        rs.getString("address_line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("country"),
                        rs.getString("base_currency")),
                companyId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static BigDecimal toBD(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        return new BigDecimal(val.toString());
    }

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String baseCurrency) {}
}
