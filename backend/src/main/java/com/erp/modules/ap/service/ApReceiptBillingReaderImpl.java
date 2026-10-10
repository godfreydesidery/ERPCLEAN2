package com.erp.modules.ap.service;

import com.erp.modules.purchases.domain.dto.ReceiptBillingReader;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP side of {@link ReceiptBillingReader} (PUR-04 / LBO-04): bills whose lines claim a goods-receipt
 * line. Bills have no void state — a corrected draft is deleted outright — so every existing bill
 * row counts. Company-scoped in SQL.
 */
@Component
@Transactional(readOnly = true)
public class ApReceiptBillingReaderImpl implements ReceiptBillingReader {

    private final NamedParameterJdbcTemplate jdbc;

    public ApReceiptBillingReaderImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> billsClaimingReceiptLines(Long companyId, Collection<String> grLineUids) {
        if (companyId == null || grLineUids == null || grLineUids.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT DISTINCT COALESCE(b.supplier_invoice_no, b.bill_number)
                FROM supplier_bill_lines l
                JOIN supplier_bills b ON b.id = l.supplier_bill_id
                WHERE l.company_id = :companyId
                  AND b.company_id = :companyId
                  AND l.gr_line_uid IN (:uids)
                """,
                new MapSqlParameterSource("companyId", companyId).addValue("uids", grLineUids),
                String.class);
    }
}
