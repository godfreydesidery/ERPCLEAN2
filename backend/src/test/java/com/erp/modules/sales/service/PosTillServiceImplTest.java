package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PosTillServiceImpl}.
 *
 * <p>Regression for ISSUE-008: createTill must persist and return a PosTillDto even when
 * no code is supplied. Before V82, {@code pos_tills.code} was NOT NULL with no generator,
 * so every INSERT failed with a constraint violation → 500.
 */
class PosTillServiceImplTest {

    private PosTillRepository tills;
    private CompanyRepository companies;
    private ScopeGuard        scopeGuard;
    private AuditService      audit;
    private PosTillServiceImpl service;

    @BeforeEach
    void setUp() {
        tills     = mock(PosTillRepository.class);
        companies = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit     = mock(AuditService.class);
        service   = new PosTillServiceImpl(tills, companies, scopeGuard, audit);

        // Provide a minimal RequestContext so actorId() doesn't NPE
        RequestContext.set(new RequestContext.Principal(1L, "operator", false, 10L, 20L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /**
     * ISSUE-008 regression: createTill happy path.
     * A till must persist and the returned DTO must carry id, uid, companyId, branchId,
     * name, and ACTIVE status. No code field is required from the caller (code is nullable
     * after V82 — the DB no longer rejects a null code).
     */
    @Test
    void createTill_persistsAndReturnsDto() {
        // Arrange
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("CMP-001")).thenReturn(Optional.of(company));

        // The saved entity must have id/uid assigned (simulate what the DB would do)
        PosTill saved = new PosTill(10L, 20L, "Till 1", 1L);
        setIdAndUid(saved, 42L, "01HXYZ0000000000000000TEST");
        when(tills.save(any(PosTill.class))).thenReturn(saved);

        CreatePosTillRequest req = new CreatePosTillRequest("CMP-001", 20L, "Till 1");

        // Act
        PosTillDto dto = service.createTill(req);

        // Assert
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.uid()).isEqualTo("01HXYZ0000000000000000TEST");
        assertThat(dto.companyId()).isEqualTo(10L);
        assertThat(dto.branchId()).isEqualTo(20L);
        assertThat(dto.name()).isEqualTo("Till 1");
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);

        verify(tills).save(any(PosTill.class));
        verify(audit).record(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Set id and uid on a UidEntity via reflection (test-only; mirrors other unit tests). */
    private static void setIdAndUid(PosTill entity, Long id, String uid) {
        try {
            var idField = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);

            // Use the protected setUid method from UidEntity
            var uidMethod = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            uidMethod.setAccessible(true);
            uidMethod.invoke(entity, uid);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Test helper setIdAndUid failed", e);
        }
    }
}
