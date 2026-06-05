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
        em.createNativeQuery(
                "TRUNCATE refresh_token, app_user, branch, company, organisation RESTART IDENTITY CASCADE")
                .executeUpdate();
    }
}
