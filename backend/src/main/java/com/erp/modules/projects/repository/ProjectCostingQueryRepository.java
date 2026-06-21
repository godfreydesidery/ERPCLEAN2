package com.erp.modules.projects.repository;

import com.erp.modules.projects.domain.dto.ProjectCostingRowDto;
import com.erp.modules.projects.domain.dto.ProjectWipRowDto;
import com.erp.modules.projects.domain.enums.ProjectCostType;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native SQL costing queries over journal_lines × chart_of_accounts (ADR-0033 D-6, NFR-PROJ-02).
 * All GROUP BY / aggregation done in SQL — never row-by-row in Java (NFR-PROJ-02).
 *
 * <p>This is NOT a JPA repository — it uses JdbcTemplate for the native GROUP BY
 * aggregate queries. It lives in the projects.repository package (allowed read-into-gl edge,
 * ADR-0033 D-12 / ADR-0020 D-12 precedent).
 */
@Repository
public class ProjectCostingQueryRepository {

    private final JdbcTemplate jdbc;

    public ProjectCostingQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sum of GL lines tagged to this project by account_type.
     * revenue = Σ (credit_amount - debit_amount) WHERE account_type = 'INCOME'
     * cost    = Σ (debit_amount  - credit_amount) WHERE account_type = 'EXPENSE'
     *
     * @return two-element array: [revenue, cost] (never null; zero if no lines)
     */
    public BigDecimal[] revenueAndCost(Long companyId, Long projectId) {
        String sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN coa.account_type = 'INCOME'
                                     THEN jl.credit_amount - jl.debit_amount  ELSE 0 END), 0) AS revenue,
                    COALESCE(SUM(CASE WHEN coa.account_type = 'EXPENSE'
                                     THEN jl.debit_amount  - jl.credit_amount ELSE 0 END), 0) AS cost
                FROM journal_lines jl
                JOIN chart_of_accounts coa ON coa.id = jl.account_id
                WHERE jl.company_id  = ?
                  AND jl.project_id  = ?
                """;
        return jdbc.queryForObject(sql, (rs, n) -> new BigDecimal[]{
                rs.getBigDecimal("revenue"),
                rs.getBigDecimal("cost")
        }, companyId, projectId);
    }

    /**
     * Cost breakdown by project_cost_type for this project.
     * NULL project_cost_type rows land in OTHER.
     */
    public List<ProjectCostingRowDto> costByType(Long companyId, Long projectId) {
        String sql = """
                SELECT
                    COALESCE(jl.project_cost_type, 'OTHER') AS cost_type,
                    COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0) AS amount
                FROM journal_lines jl
                JOIN chart_of_accounts coa ON coa.id = jl.account_id
                WHERE jl.company_id  = ?
                  AND jl.project_id  = ?
                  AND coa.account_type = 'EXPENSE'
                GROUP BY COALESCE(jl.project_cost_type, 'OTHER')
                """;
        return jdbc.query(sql, (rs, n) -> new ProjectCostingRowDto(
                ProjectCostType.valueOf(rs.getString("cost_type")),
                rs.getBigDecimal("amount")
        ), companyId, projectId);
    }

    /**
     * Cross-project WIP rows for a company (and optionally a branch). WIP = max(0, cost − billed),
     * floored at zero (BR-PROJ-07). Returns only projects that have at least one tagged journal line.
     *
     * <p>Branch isolation (ADR-0033 D-2 / NFR-PROJ-01): when {@code branchId} is non-null,
     * results are restricted to projects whose {@code branch_id} matches the caller's branch.
     * Passing {@code null} returns all branches (root/admin cross-branch view).
     */
    public List<ProjectWipRowDto> wipReport(Long companyId, Long branchId) {
        // Build SQL with an optional branch filter — use a StringBuilder to avoid
        // text-block concatenation that produced a missing whitespace before GROUP BY
        // when branchFilter was empty (PROJECTS-040 SQL failure).
        StringBuilder sql = new StringBuilder("""
                SELECT
                    p.uid              AS project_uid,
                    p.project_number   AS project_number,
                    p.name             AS name,
                    COALESCE(SUM(CASE WHEN coa.account_type = 'EXPENSE'
                                     THEN jl.debit_amount - jl.credit_amount ELSE 0 END), 0) AS cost_incurred,
                    COALESCE(SUM(CASE WHEN coa.account_type = 'INCOME'
                                     THEN jl.credit_amount - jl.debit_amount ELSE 0 END), 0) AS billed,
                    p.currency         AS currency
                FROM projects p
                JOIN journal_lines jl      ON jl.project_id = p.id
                JOIN chart_of_accounts coa ON coa.id = jl.account_id
                WHERE p.company_id = ?
                  AND jl.company_id = ?
                """);
        if (branchId != null) {
            sql.append("  AND p.branch_id = ?\n");
        }
        sql.append("""
                GROUP BY p.uid, p.project_number, p.name, p.currency
                ORDER BY p.project_number
                """);
        Object[] params = branchId != null
                ? new Object[]{companyId, companyId, branchId}
                : new Object[]{companyId, companyId};
        return jdbc.query(sql.toString(), (rs, n) -> {
            BigDecimal cost   = rs.getBigDecimal("cost_incurred");
            BigDecimal billed = rs.getBigDecimal("billed");
            // Null-guard: COALESCE ensures non-null for aggregate groups, but
            // guard defensively (PROJECTS-040).
            if (cost == null)   cost   = BigDecimal.ZERO;
            if (billed == null) billed = BigDecimal.ZERO;
            BigDecimal wip = cost.subtract(billed).max(BigDecimal.ZERO);
            return new ProjectWipRowDto(
                    rs.getString("project_uid"),
                    rs.getString("project_number"),
                    rs.getString("name"),
                    cost, billed, wip,
                    rs.getString("currency")
            );
        }, params);
    }
}
