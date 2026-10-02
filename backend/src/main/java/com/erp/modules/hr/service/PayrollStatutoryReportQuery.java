package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.PayrollRunStatutoryReportDto;
import com.erp.modules.hr.domain.dto.PayrollStatutoryPeriodReportDto;
import com.erp.modules.hr.domain.dto.StatutoryLineDto;
import com.erp.modules.hr.domain.dto.StatutorySummaryDto;
import com.erp.modules.hr.domain.dto.StatutoryTotalsDto;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll Statutory Summary (FR-HR-23) — PAYE / NSSF (employee + employer) / WCF / SDL / HESLB,
 * per run with a per-employee breakdown, and per pay-date range with one row per run.
 *
 * <p>Every figure is summed from {@code payroll_lines}: the lines the payslips, the GL posting and
 * the bank file are all built from, so this report cannot disagree with them. Scalar SQL rather than
 * the JPA repositories because the period view is a grouped aggregate; the company's base currency
 * is the one cross-module read (scalar, no entity import — module-boundary rule).
 *
 * <p>Visibility: per-employee pay amounts are already disclosed to {@code HR.PAYROLL.VIEW} by the
 * run's {@code /lines} endpoint, so this report discloses nothing new at that gate. The one
 * finer-grained HR code, {@code HR.EMPLOYEE.PAYEE.VIEW}, covers bank-account / mobile-money numbers
 * only — none of which appear here.
 */
@Component
@Transactional(readOnly = true)
public class PayrollStatutoryReportQuery {

    /** Runs whose figures are final and therefore count towards a filing period. */
    static final Set<PayrollRunStatus> COUNTED =
            EnumSet.of(PayrollRunStatus.APPROVED, PayrollRunStatus.POSTED, PayrollRunStatus.PAID);

    private static final String DEFAULT_CURRENCY = "TZS";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public PayrollStatutoryReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // Per run
    // -------------------------------------------------------------------------

    public PayrollRunStatutoryReportDto forRun(String runUid) {
        RunHeader run = loadRun(runUid);
        // Scope from the LOADED run, never from a caller parameter.
        scopeGuard.assertCanActIn(RequestContext.get(), run.companyId());

        List<StatutoryLineDto> lines = jdbc.query(
                """
                SELECT l.employee_number, l.employee_name, l.department_name,
                       e.tin, e.nssf_number, e.heslb_number,
                       l.gross_amount, l.paye_amount, l.nssf_employee_amount, l.nssf_employer_amount,
                       l.wcf_employer_amount, l.sdl_employer_amount, l.heslb_amount, l.net_amount
                FROM payroll_lines l
                LEFT JOIN employees e ON e.id = l.employee_id AND e.company_id = l.company_id
                WHERE l.payroll_run_id = ? AND l.company_id = ?
                ORDER BY l.employee_number, l.id
                """,
                (rs, n) -> new StatutoryLineDto(
                        rs.getString("employee_number"),
                        rs.getString("employee_name"),
                        rs.getString("department_name"),
                        rs.getString("tin"),
                        rs.getString("nssf_number"),
                        rs.getString("heslb_number"),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("paye_amount"),
                        rs.getBigDecimal("nssf_employee_amount"),
                        rs.getBigDecimal("nssf_employer_amount"),
                        rs.getBigDecimal("wcf_employer_amount"),
                        rs.getBigDecimal("sdl_employer_amount"),
                        rs.getBigDecimal("heslb_amount"),
                        rs.getBigDecimal("net_amount")),
                run.id(), run.companyId());

        BigDecimal gross = BigDecimal.ZERO, paye = BigDecimal.ZERO, nssfEe = BigDecimal.ZERO,
                nssfEr = BigDecimal.ZERO, wcf = BigDecimal.ZERO, sdl = BigDecimal.ZERO,
                heslb = BigDecimal.ZERO, net = BigDecimal.ZERO;
        for (StatutoryLineDto l : lines) {
            gross  = gross.add(l.grossAmount());
            paye   = paye.add(l.payeAmount());
            nssfEe = nssfEe.add(l.nssfEmployeeAmount());
            nssfEr = nssfEr.add(l.nssfEmployerAmount());
            wcf    = wcf.add(l.wcfAmount());
            sdl    = sdl.add(l.sdlAmount());
            heslb  = heslb.add(l.heslbAmount());
            net    = net.add(l.netAmount());
        }
        StatutorySummaryDto summary = new StatutorySummaryDto(
                run.uid(), run.runNumber(), run.periodYear(), run.periodMonth(), run.payDate(),
                run.status(), lines.size(),
                gross, paye, nssfEe, nssfEr, wcf, sdl, heslb, net, nssfEr.add(wcf).add(sdl));

        return new PayrollRunStatutoryReportDto(run.companyId(), summary, lines,
                !COUNTED.contains(run.status()),
                baseCurrency(run.companyId()), Instant.now().toString());
    }

    // -------------------------------------------------------------------------
    // Per period
    // -------------------------------------------------------------------------

    public PayrollStatutoryPeriodReportDto forPeriod(Long companyId, LocalDate fromDate, LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Choose the dates this report should cover.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be before the start date.");
        }

        // One row per run in the window, aggregated from its lines. LEFT JOIN so a run with no
        // lines (never calculated) still appears — it is then classified, not silently dropped.
        List<StatutorySummaryDto> all = jdbc.query(
                """
                SELECT r.uid, r.run_number, r.period_year, r.period_month, r.pay_date, r.status,
                       COUNT(l.id)                               AS employee_count,
                       COALESCE(SUM(l.gross_amount), 0)          AS gross,
                       COALESCE(SUM(l.paye_amount), 0)           AS paye,
                       COALESCE(SUM(l.nssf_employee_amount), 0)  AS nssf_ee,
                       COALESCE(SUM(l.nssf_employer_amount), 0)  AS nssf_er,
                       COALESCE(SUM(l.wcf_employer_amount), 0)   AS wcf,
                       COALESCE(SUM(l.sdl_employer_amount), 0)   AS sdl,
                       COALESCE(SUM(l.heslb_amount), 0)          AS heslb,
                       COALESCE(SUM(l.net_amount), 0)            AS net
                FROM payroll_runs r
                LEFT JOIN payroll_lines l ON l.payroll_run_id = r.id AND l.company_id = r.company_id
                WHERE r.company_id = ?
                  AND r.pay_date >= ?
                  AND r.pay_date <= ?
                GROUP BY r.id, r.uid, r.run_number, r.period_year, r.period_month, r.pay_date, r.status
                ORDER BY r.pay_date, r.run_number
                """,
                (rs, n) -> periodRow(rs),
                companyId, fromDate, toDate);

        List<StatutorySummaryDto> counted = new ArrayList<>();
        int pending = 0;
        int reversed = 0;
        for (StatutorySummaryDto r : all) {
            if (COUNTED.contains(r.status())) {
                counted.add(r);
            } else if (r.status() == PayrollRunStatus.REVERSED) {
                reversed++;
            } else {
                pending++;
            }
        }

        int payslips = 0;
        BigDecimal gross = BigDecimal.ZERO, paye = BigDecimal.ZERO, nssfEe = BigDecimal.ZERO,
                nssfEr = BigDecimal.ZERO, wcf = BigDecimal.ZERO, sdl = BigDecimal.ZERO,
                heslb = BigDecimal.ZERO, net = BigDecimal.ZERO, employer = BigDecimal.ZERO;
        for (StatutorySummaryDto r : counted) {
            payslips += r.employeeCount();
            gross    = gross.add(r.grossTotal());
            paye     = paye.add(r.payeTotal());
            nssfEe   = nssfEe.add(r.nssfEmployeeTotal());
            nssfEr   = nssfEr.add(r.nssfEmployerTotal());
            wcf      = wcf.add(r.wcfTotal());
            sdl      = sdl.add(r.sdlTotal());
            heslb    = heslb.add(r.heslbTotal());
            net      = net.add(r.netTotal());
            employer = employer.add(r.employerCostTotal());
        }
        StatutoryTotalsDto totals = new StatutoryTotalsDto(counted.size(), payslips,
                gross, paye, nssfEe, nssfEr, wcf, sdl, heslb, net, employer);

        return new PayrollStatutoryPeriodReportDto(fromDate.toString(), toDate.toString(),
                counted, totals, pending, reversed, baseCurrency(companyId), Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private static StatutorySummaryDto periodRow(ResultSet rs) throws SQLException {
        BigDecimal nssfEr = rs.getBigDecimal("nssf_er");
        BigDecimal wcf    = rs.getBigDecimal("wcf");
        BigDecimal sdl    = rs.getBigDecimal("sdl");
        return new StatutorySummaryDto(
                rs.getString("uid"),
                rs.getString("run_number"),
                rs.getInt("period_year"),
                rs.getInt("period_month"),
                rs.getObject("pay_date", LocalDate.class),
                PayrollRunStatus.valueOf(rs.getString("status")),
                rs.getInt("employee_count"),
                rs.getBigDecimal("gross"),
                rs.getBigDecimal("paye"),
                rs.getBigDecimal("nssf_ee"),
                nssfEr,
                wcf,
                sdl,
                rs.getBigDecimal("heslb"),
                rs.getBigDecimal("net"),
                nssfEr.add(wcf).add(sdl));
    }

    private RunHeader loadRun(String runUid) {
        List<RunHeader> found = jdbc.query(
                """
                SELECT id, uid, company_id, run_number, period_year, period_month, pay_date, status
                FROM payroll_runs
                WHERE uid = ?
                """,
                (rs, n) -> new RunHeader(
                        rs.getLong("id"),
                        rs.getString("uid"),
                        rs.getLong("company_id"),
                        rs.getString("run_number"),
                        rs.getInt("period_year"),
                        rs.getInt("period_month"),
                        rs.getObject("pay_date", LocalDate.class),
                        PayrollRunStatus.valueOf(rs.getString("status"))),
                runUid);
        if (found.isEmpty()) {
            throw new NotFoundException("That payroll run could not be found.");
        }
        return found.get(0);
    }

    private String baseCurrency(Long companyId) {
        List<String> found = jdbc.queryForList(
                "SELECT base_currency FROM companies WHERE id = ?", String.class, companyId);
        return found.isEmpty() || found.get(0) == null ? DEFAULT_CURRENCY : found.get(0);
    }

    private record RunHeader(Long id, String uid, Long companyId, String runNumber,
                             int periodYear, int periodMonth, LocalDate payDate,
                             PayrollRunStatus status) {}
}
