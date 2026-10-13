package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.platform.common.api.NotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Names the customer on AR open items and receipts, and resolves a customer uid to its id.
 *
 * <p>AR rows store only the numeric customer id, so screens used to preload a customer list and
 * fell back to showing the raw id once the company had more customers than the preload (ARC-33).
 * The name is read here at display time instead, one query per page. Cross-module read = scalar
 * native SQL, always company-scoped (module boundary rule; same pattern as
 * {@link ArDocumentNumberResolver}).
 */
@Component
public class ArCustomerNames {

    /** A customer's external identity and display name. */
    public record CustomerName(Long id, String uid, String code, String displayName) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ArCustomerNames(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * The id of the customer with {@code customerUid} inside {@code companyId}. A uid from another
     * company is "not found" — the caller has already scope-checked the company.
     */
    public Long idOf(Long companyId, String customerUid) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM customers WHERE company_id = :companyId AND uid = :uid",
                new MapSqlParameterSource("companyId", companyId).addValue("uid", customerUid.trim()),
                Long.class);
        if (ids.isEmpty()) {
            throw new NotFoundException("Customer not found.");
        }
        return ids.get(0);
    }

    /** The customers with these ids inside {@code companyId}, keyed by id. One query. */
    public Map<Long, CustomerName> byIds(Long companyId, Collection<Long> customerIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : customerIds) {
            if (id != null) ids.add(id);
        }
        Map<Long, CustomerName> out = new HashMap<>();
        if (ids.isEmpty() || companyId == null) {
            return out;
        }
        jdbc.query("""
                SELECT id, uid, code, display_name FROM customers
                WHERE company_id = :companyId AND id IN (:ids)
                """,
                new MapSqlParameterSource("companyId", companyId).addValue("ids", ids),
                rs -> {
                    long id = rs.getLong("id");
                    out.put(id, new CustomerName(id, rs.getString("uid"), rs.getString("code"),
                            rs.getString("display_name")));
                });
        return out;
    }

    /** The open items, each carrying its customer's uid, code and name. */
    public List<ArInvoiceDto> fillInvoices(Long companyId, List<ArInvoiceDto> items) {
        Map<Long, CustomerName> names = byIds(companyId,
                items.stream().map(ArInvoiceDto::customerId).toList());
        List<ArInvoiceDto> out = new ArrayList<>(items.size());
        for (ArInvoiceDto i : items) {
            CustomerName n = names.get(i.customerId());
            out.add(n != null ? i.withCustomer(n.uid(), n.code(), n.displayName()) : i);
        }
        return out;
    }

    /** The receipts, each carrying its customer's uid, code and name. */
    public List<ArReceiptDto> fillReceipts(Long companyId, List<ArReceiptDto> items) {
        Map<Long, CustomerName> names = byIds(companyId,
                items.stream().map(ArReceiptDto::customerId).toList());
        List<ArReceiptDto> out = new ArrayList<>(items.size());
        for (ArReceiptDto r : items) {
            CustomerName n = names.get(r.customerId());
            out.add(n != null ? r.withCustomer(n.uid(), n.code(), n.displayName()) : r);
        }
        return out;
    }
}
