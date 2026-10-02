package com.erp.modules.purchases.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.platform.common.api.NotFoundException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared plumbing for the four purchase reports (Goods Received Register, Purchases by Supplier,
 * Open Purchase Orders, Purchase Price Variance): the company letterhead, the period window in the
 * company's own time zone, and company-scoped resolution of a caller-supplied filter uid.
 *
 * <p>Scalar native SQL only — the purchase reports read suppliers, products, branches and supplier
 * bills, which belong to other modules, and the module boundary forbids importing their entities or
 * services (same pattern as {@code SalesReportQuery}).
 */
@Component
public class PurchaseReportSupport {

    static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    static final String CURRENCY_FALLBACK = "TZS";

    private final JdbcTemplate jdbc;

    public PurchaseReportSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The company row a report prints at its head, plus the two settings every report needs. */
    record Company(ReportCompanyHeaderDto header, ZoneId zone, String baseCurrency) {

        /** Start of {@code day} in the company's zone — bound as OffsetDateTime (timestamptz). */
        OffsetDateTime startOf(LocalDate day) {
            return day.atStartOfDay(zone).toOffsetDateTime();
        }

        /** Exclusive end of {@code day}: the start of the next one. */
        OffsetDateTime endOf(LocalDate day) {
            return day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }
    }

    /** A resolved filter: the internal id the SQL binds, and the name the header prints. */
    record Ref(Long id, String name) {}

    Company loadCompany(Long companyId) {
        List<Company> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> {
                    String tz = rs.getString("time_zone");
                    String ccy = rs.getString("base_currency");
                    return new Company(
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
                            ZoneId.of(tz != null && !tz.isBlank() ? tz : DEFAULT_TIME_ZONE),
                            ccy != null && !ccy.isBlank() ? ccy : CURRENCY_FALLBACK);
                },
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    /**
     * Resolves an optional filter uid TOGETHER with the caller's company. An unknown uid — or one
     * that belongs to another company — is a 404, never a filter that silently widens to "all".
     *
     * <p>{@code table} and {@code nameColumn} are compile-time constants at every call site, never
     * caller input.
     */
    Ref resolve(String table, String nameColumn, String uid, Long companyId, String entityName) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<Ref> found = jdbc.query(
                "SELECT id, " + nameColumn + " AS name FROM " + table
                        + " WHERE uid = ? AND company_id = ?",
                (rs, rowNum) -> new Ref(rs.getLong("id"), rs.getString("name")),
                uid.trim(), companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of(entityName, uid);
        }
        return found.get(0);
    }

    /** The two dates a period report needs, checked before any SQL runs. */
    static void requirePeriod(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Please choose a start date and an end date.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be earlier than the start date.");
        }
    }
}
