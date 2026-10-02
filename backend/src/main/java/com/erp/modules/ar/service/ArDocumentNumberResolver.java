package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Names an AR open item that has no document number of its own.
 *
 * <p>A credit sale's open item was created without {@code document_no} until 2026-10 (the handler
 * now stamps the sales invoice number on it). Those older rows still carry the sales invoice they
 * came from ({@code source_invoice_uid}), so the number is read from there at display time instead
 * of being back-filled into the table: history is fixed for every screen that shows it, with no
 * data repair. Cross-module read = scalar native SQL, company-scoped (module boundary rule).
 */
@Component
public class ArDocumentNumberResolver {

    private final NamedParameterJdbcTemplate jdbc;

    public ArDocumentNumberResolver(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * The same items, each missing document number filled from its source sales invoice. Items
     * that already have a number, or have no source invoice, come back unchanged. One query.
     */
    public List<ArInvoiceDto> fill(Long companyId, List<ArInvoiceDto> items) {
        List<String> missing = new ArrayList<>();
        for (ArInvoiceDto i : items) {
            if ((i.documentNo() == null || i.documentNo().isBlank())
                    && i.sourceInvoiceUid() != null && !i.sourceInvoiceUid().isBlank()) {
                missing.add(i.sourceInvoiceUid());
            }
        }
        if (missing.isEmpty() || companyId == null) {
            return items;
        }
        Map<String, String> numbers = new HashMap<>();
        jdbc.query("""
                SELECT uid, invoice_number FROM sales_invoices
                WHERE company_id = :companyId AND uid IN (:uids) AND invoice_number IS NOT NULL
                """,
                new MapSqlParameterSource("companyId", companyId).addValue("uids", missing),
                rs -> {
                    numbers.put(rs.getString("uid"), rs.getString("invoice_number"));
                });
        if (numbers.isEmpty()) {
            return items;
        }
        List<ArInvoiceDto> out = new ArrayList<>(items.size());
        for (ArInvoiceDto i : items) {
            String n = (i.documentNo() == null || i.documentNo().isBlank())
                    ? numbers.get(i.sourceInvoiceUid()) : null;
            out.add(n != null ? i.withDocumentNo(n) : i);
        }
        return out;
    }

    /** Single-item convenience over {@link #fill}. */
    public ArInvoiceDto fill(ArInvoiceDto item) {
        return fill(item.companyId(), List.of(item)).get(0);
    }
}
