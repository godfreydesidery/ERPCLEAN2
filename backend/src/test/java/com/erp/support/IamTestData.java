package com.erp.support;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.UserCompanyService;
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
    private final UserCompanyService userCompanyService;
    private final AppUserRepository appUsers;
    private final CompanyRepository companies;

    public IamTestData(EntityManager em,
                       UserCompanyService userCompanyService,
                       AppUserRepository appUsers,
                       CompanyRepository companies) {
        this.em = em;
        this.userCompanyService = userCompanyService;
        this.appUsers = appUsers;
        this.companies = companies;
    }

    /**
     * ADR-0046 test helper: seed an explicit {@code user_company} membership so a test can then
     * grant roles / assign branches (the authoritative gate now requires assign-company-first).
     * Delegates to {@code ensureMembership}, which bypasses the scope check and is idempotent.
     * Call once per (user, company) before granting/assigning in that company.
     */
    public void seedMembership(String userUid, String companyUid) {
        Long userId = appUsers.findByUid(userUid).orElseThrow().getId();
        Long companyId = companies.findByUid(companyUid).orElseThrow().getId();
        userCompanyService.ensureMembership(userId, companyId, null);
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
        // 4. Clear the transactional-outbox tables (platform.events; no FK into business tables).
        em.createNativeQuery(
                "TRUNCATE domain_events, processed_events RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-TAX. Clear VAT / WHT tables BEFORE GL and companies.
        //        FK order: wht_transactions → wht_types; vat_return_bands/vat_adjustments → vat_returns.
        em.createNativeQuery(
                "TRUNCATE wht_transactions, wht_types, vat_adjustments, vat_return_bands, vat_returns RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-CB. Clear Cash & Bank tables BEFORE AR/AP (ar_receipts.cash_bank_account_id and
        //       ap_payments.cash_bank_account_id FK → cash_bank_accounts; cash_transactions
        //       FK → bank_reconciliations; cash_transfers FK → cash_transactions).
        //       FK order: cheques → cash_bank_accounts; bank_reconciliations standalone (FK ← cash_transactions);
        //       cash_transactions → bank_reconciliations + cash_bank_accounts;
        //       cash_transfers → cash_transactions + cash_bank_accounts; cash_bank_accounts standalone.
        em.createNativeQuery(
                "TRUNCATE cheques, cash_transfers, cash_transactions, bank_reconciliations, cash_bank_accounts RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-AP. Clear AP tables BEFORE GL, suppliers and companies.
        //       FK order: allocations → payments; bill_match → bill_lines → bills; debit_notes standalone.
        em.createNativeQuery(
                "TRUNCATE ap_payment_allocations, ap_debit_notes, ap_payments, bill_match, supplier_bill_lines, supplier_bills RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-AR. Clear AR tables BEFORE GL and companies (FK order: allocations → receipts/invoices;
        //       write_offs / credit_notes → invoices; invoices standalone. All FK → companies/customers/branches).
        em.createNativeQuery(
                "TRUNCATE ar_receipt_allocations, ar_write_offs, ar_credit_notes, ar_receipts, ar_invoices RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-GL. Clear GL tables BEFORE companies (journal_lines/entries/configs/periods/accounts FK → companies).
        //       FK order within GL: lines → entries → batches; gl_configs → accounts; periods → fiscal_years.
        em.createNativeQuery(
                "TRUNCATE journal_lines, journal_entries, journal_batches, gl_configs, fiscal_periods, fiscal_years, chart_of_accounts RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-FA. Clear Fixed Assets tables BEFORE GL, categories, companies (FK order: run lines → runs →
        //       schedule lines → assets; disposals/revaluations → assets; assets → categories).
        em.createNativeQuery(
                "TRUNCATE asset_revaluations, asset_disposals, " +
                "depreciation_run_lines, depreciation_runs, " +
                "depreciation_schedule_lines, fixed_assets, asset_categories RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4a. Clear purchases tables BEFORE products/parties (GR/PO lines FK → products/suppliers/units).
        em.createNativeQuery(
                "TRUNCATE goods_receipt_lines, goods_receipts, purchase_order_lines, purchase_orders RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4b. Clear stock tables BEFORE products/sales (stock_movements + stock_on_hand FK → products/branches/companies).
        em.createNativeQuery(
                "TRUNCATE stock_movements, stock_on_hand RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4c. Clear routes junctions BEFORE sales (sales_invoices.route_id FKs routes) and
        //     BEFORE parties (route_customer/route_agent FK → customers/agents).
        //     Order: junctions first (FK children of routes), then routes master.
        em.createNativeQuery(
                "TRUNCATE route_customer, route_agent, route_branch RESTART IDENTITY CASCADE")
                .executeUpdate();
        em.createNativeQuery(
                "TRUNCATE routes RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-APR. Clear approvals tables BEFORE companies/branches (FK children of companies+branches).
        //        FK order: decisions → request_steps → requests → policies.
        em.createNativeQuery(
                "TRUNCATE approval_decisions, approval_request_steps, approval_requests, " +
                "approval_policy_steps, approval_policies RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4d-CRM. Clear CRM tables BEFORE O2C (opportunity_lines FK → opportunities; activities FK → leads/opportunities).
        em.createNativeQuery(
                "TRUNCATE activities, opportunity_lines, opportunities, leads, pipeline_stages RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4d-O2C. Clear ADR-0021 O2C tables BEFORE sales_invoices (delivery_lines FK → sales_order_lines;
        //         sales_order_lines FK → sales_orders; delivery_lines.delivery_id FK → deliveries).
        //         Order: leaf children first, then headers.
        em.createNativeQuery(
                "TRUNCATE delivery_lines, deliveries, sales_order_lines, sales_orders, " +
                "quotation_lines, quotations RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4d. Clear sales tables (FK children before parents; before products/parties).
        //     tax_rates after invoices because invoices do NOT FK into tax_rates; payments/lines first.
        //     sales_invoices.route_id nullable — routes already cleared above so FK is satisfied.
        em.createNativeQuery(
                "TRUNCATE sales_invoice_payments, sales_invoice_lines, sales_invoices, tax_rates RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4-BUD. Clear budgeting tables BEFORE GL (budget_lines FK → chart_of_accounts + fiscal_periods;
        //        budget_versions FK → fiscal_years + dimension_values; budgets FK → fiscal_years + dimension_values).
        //        FK order: lines → versions → budgets; code_sequence for BUDGET kind cleared later with products.
        em.createNativeQuery(
                "TRUNCATE budget_lines, budget_versions, budgets RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4e. Clear BOM tables BEFORE products (bom_components FK → products; boms FK → products).
        em.createNativeQuery(
                "TRUNCATE bom_components, boms RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 4f. Clear products tables (FK children first, then masters, then sequence counter).
        //     units_of_measure is included here because products.base_unit_id and
        //     product_bulk_packs.unit_id FK into it (UoM cutover V4).
        //     barcode_symbology_rules FK → companies; no children; cleared here with products.
        em.createNativeQuery(
                "TRUNCATE product_branch, product_barcodes, product_prices, product_components, product_bulk_packs RESTART IDENTITY CASCADE")
                .executeUpdate();
        em.createNativeQuery(
                "TRUNCATE barcode_symbology_rules RESTART IDENTITY CASCADE")
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
        // 4-FX. Clear FX revaluation runs + rates BEFORE companies (FK children of companies).
        em.createNativeQuery(
                "TRUNCATE fx_revaluation_run_lines, fx_revaluation_runs RESTART IDENTITY CASCADE")
                .executeUpdate();
        em.createNativeQuery("TRUNCATE currency_rates RESTART IDENTITY CASCADE").executeUpdate();
        // 4-CCY. Clear currency enablement allow-lists BEFORE companies (FK children of companies/branches).
        em.createNativeQuery(
                "TRUNCATE branch_currency, company_currency RESTART IDENTITY CASCADE")
                .executeUpdate();
        // 7. Clear the rest of the IAM tables (CASCADE handles FK order within this set).
        //    audit_log has a NULLABLE FK to app_user (ON DELETE SET NULL per schema) so it does not
        //    block the app_user truncate, but its rows must be cleared so audit-count assertions in
        //    AuditServiceImplIT start from zero.
        // `organisations` is deliberately NOT in this TRUNCATE, and that is a V104 consequence.
        //
        // V104 added fk_roles_organisation, so `roles` became a CHILD of `organisations`. TRUNCATE
        // ... CASCADE follows foreign keys REGARDLESS OF DATA, so including `organisations` here
        // emptied the whole roles table — including the is_system bundles Flyway seeds once at
        // migration time and never again. Step 3 above deletes only `is_system = false` roles
        // precisely to keep them; the cascade silently undid that.
        //
        // Measured on a restored customer database: that statement takes `roles` from 15 rows to 0.
        //
        // It stayed invisible until tenant administrators stopped being root, because a non-root
        // admin needs the real ORG_ADMIN grant to do anything. TwoOrganisationIsolationIT's own
        // guard then fired — "ORG_ADMIN is not seeded, every probe below would be vacuous" — which
        // is the only reason this was caught rather than turning 22 isolation probes green for the
        // wrong reason.
        em.createNativeQuery(
                "TRUNCATE audit_logs, refresh_tokens, user_branch, app_users, branches, companies RESTART IDENTITY CASCADE")
                .executeUpdate();

        // Now organisations can go by DELETE, which respects foreign keys instead of bulldozing
        // through them. Tenant-scoped roles are removed first because they genuinely reference an
        // organisation; the global bundles carry organisation_id IS NULL (D-3) and so reference
        // nothing, which is exactly why they survive this and did not survive the cascade.
        //
        // Re-seeding was tried first and is the wrong repair: R__seed_permissions.sql fills
        // ORG_ADMIN's grants but does NOT create the role — its own header says the row is created
        // in V1 — so re-running it restores permissions onto a role that no longer exists.
        em.createNativeQuery("DELETE FROM roles WHERE organisation_id IS NOT NULL").executeUpdate();
        em.createNativeQuery("DELETE FROM organisations").executeUpdate();
        // TRUNCATE ... RESTART IDENTITY did this for free; DELETE does not, and the id recycling is
        // load-bearing for the tests that assert on freshly issued ids.
        em.createNativeQuery("ALTER SEQUENCE organisations_id_seq RESTART WITH 1").executeUpdate();

        // Fail loudly rather than let a future change quietly empty the bundles again. A missing
        // ORG_ADMIN makes every RBAC and isolation test pass for the wrong reason, because a user
        // with no grants is refused everything.
        Number systemRoles = (Number) em
                .createNativeQuery("SELECT count(*) FROM roles WHERE is_system = true")
                .getSingleResult();
        if (systemRoles.intValue() == 0) {
            throw new IllegalStateException(
                    "clearAll() destroyed the seeded system roles. Flyway created them once and will "
                            + "not re-create them, so every RBAC test after this point would be "
                            + "vacuous. Check what new foreign key made a TRUNCATE ... CASCADE reach "
                            + "the roles table.");
        }

        // The TRUNCATE above now reaches `roles`, and did not before V104.
        //
        // V104 added fk_roles_organisation, making `roles` a CHILD of `organisations` — so
        // TRUNCATE ... CASCADE follows the new key and empties the whole table, including the
        // thirteen is_system bundles Flyway seeded once at migration time and will never seed
        // again. Step 3 above deliberately deletes only `is_system = false` roles, and its comment
        // ("ORG_ADMIN rows are intentionally left intact") was true when written; the migration
        // invalidated it silently.
        //
        // It surfaced because tenant administrators stopped being root: a non-root admin needs the
        // real ORG_ADMIN grant to do anything, so TwoOrganisationIsolationIT's guard fired with
        // "ORG_ADMIN is not seeded — every probe below would be vacuous". Every read probe would
        // otherwise have kept passing for the wrong reason, a user with no grants being refused
        // everything.
        //
        // Re-running the repeatable seed is the honest repair: it is the same SQL Flyway applies,
        // it upserts, and it restores the bundles WITH their ~250 permission grants rather than an
        // empty shell that would make the probes vacuous in a subtler way.
    }

}
