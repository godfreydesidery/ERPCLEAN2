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
        // 1. Clear all role grants (FK child of role, app_user, company, branch).
        em.createNativeQuery("TRUNCATE user_role RESTART IDENTITY CASCADE").executeUpdate();
        // 2. Remove role_permission rows for test-created (non-system) roles so that the role
        //    delete below is not blocked by fk_role_permission_role. ORG_ADMIN (is_system) rows
        //    are intentionally left intact — Flyway seeded them once and won't re-seed mid-run.
        em.createNativeQuery(
                "DELETE FROM role_permission WHERE role_id IN (SELECT id FROM role WHERE is_system = false)")
                .executeUpdate();
        // 3. Delete test-created roles (system roles stay).
        em.createNativeQuery("DELETE FROM role WHERE is_system = false").executeUpdate();
        // 4. Clear the rest of the IAM tables (CASCADE handles FK order within this set).
        //    audit_log has a NULLABLE FK to app_user (ON DELETE SET NULL per schema) so it does not
        //    block the app_user truncate, but its rows must be cleared so audit-count assertions in
        //    AuditServiceImplIT start from zero.
        em.createNativeQuery(
                "TRUNCATE audit_log, refresh_token, user_branch, app_user, branch, company, organisation RESTART IDENTITY CASCADE")
                .executeUpdate();
    }
}
