package com.erp.support;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FK-safe cleanup of IAM tables for integration tests. TRUNCATE ... CASCADE wipes every IAM table
 * in one statement regardless of FK order (including the self-referencing refresh_token chain and
 * the app_user → branch default FK), so each test starts from a known-empty state without
 * hand-ordering deletes in every {@code @BeforeEach}.
 */
@Component
public class IamTestData {

    private final EntityManager em;

    public IamTestData(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public void clearAll() {
        // 1. Clear all role grants (FK child of roles, app_users, companies, branches).
        em.createNativeQuery("TRUNCATE user_role RESTART IDENTITY CASCADE").executeUpdate();
        // 2. Remove role_permission rows for test-created (non-system) roles so that the role
        //    delete below is not blocked by fk_role_permission_role. ORG_ADMIN (is_system) rows
        //    are intentionally left intact — Flyway seeded them once and won't re-seed mid-run.
        em.createNativeQuery(
                "DELETE FROM role_permission WHERE role_id IN (SELECT id FROM roles WHERE is_system = false)")
                .executeUpdate();
        // 3. Delete test-created roles (system roles stay).
        em.createNativeQuery("DELETE FROM roles WHERE is_system = false").executeUpdate();
        // 4a. Clear sales tables (FK children before parents; before products/parties).
        //     tax_rates after invoices because invoices do NOT FK into tax_rates; payments/lines first.
        em.createNativeQuery(
                "TRUNCATE sales_invoice_payments, sales_invoice_lines, sales_invoices, tax_rates RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4b. Clear products tables (FK children first, then masters, then sequence counter).
        //    units_of_measure is included here because products.base_unit_id and
        //    product_bulk_packs.unit_id FK into it (UoM cutover V4).
        em.createNativeQuery(
                "TRUNCATE product_branch, product_barcodes, product_prices, product_components, product_bulk_packs RESTART IDENTITY CASCADE")
                .executeUpdate();
        em.createNativeQuery(
                "TRUNCATE products, price_lists, units_of_measure, code_sequence RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 5. Clear parties link tables first (FK children of master party tables and branches/app_users).
        em.createNativeQuery(
                "TRUNCATE customer_branch, supplier_branch, agent_branch, other_party_branch RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 6. Clear party master tables and the code-sequence counter.
        em.createNativeQuery(
                "TRUNCATE customers, suppliers, agents, other_parties, party_code_sequence RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 7. Clear the rest of the IAM tables (CASCADE handles FK order within this set).
        //    audit_log has a NULLABLE FK to app_user (ON DELETE SET NULL per schema) so it does not
        //    block the app_user truncate, but its rows must be cleared so audit-count assertions in
        //    AuditServiceImplIT start from zero.
        em.createNativeQuery(
                "TRUNCATE audit_logs, refresh_tokens, user_branch, app_users, branches, companies, organisations RESTART IDENTITY CASCADE")
                .executeUpdate();
    }
}
