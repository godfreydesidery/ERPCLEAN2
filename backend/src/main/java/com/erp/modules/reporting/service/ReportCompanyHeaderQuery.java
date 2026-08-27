package com.erp.modules.reporting.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.platform.common.api.NotFoundException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the company block that appears at the top of a printed register or document.
 *
 * <p>Every printable thing needs the same block, and until now every report query grew its own
 * private copy of this query — seven of them at the time of writing. This is the shared one, next
 * to the DTO it returns, so a new printable document does not have to make an eighth. The existing
 * seven are deliberately left alone here: replacing them is a refactor of working reporting code,
 * which does not belong in a customer fix.
 */
@Service
@Transactional(readOnly = true)
public class ReportCompanyHeaderQuery {

    private final JdbcTemplate jdbc;

    public ReportCompanyHeaderQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReportCompanyHeaderDto forCompany(Long companyId) {
        List<ReportCompanyHeaderDto> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new ReportCompanyHeaderDto(
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
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }
}
