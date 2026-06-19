package com.erp.modules.sales.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the POS-sale idempotency primitive against real Postgres (ADR-0042 D-1): the
 * reserve-before-process claim ({@code INSERT ... ON CONFLICT DO NOTHING}) returns 1 for the first
 * caller and 0 for a duplicate of the same {@code (company, key)}, and the invoice uid stamped onto a
 * reserved marker is readable back. This is the correctness that makes a retried POS sale return the
 * original invoice instead of double-posting.
 */
class PosSaleIdempotencyRepositoryIT extends PostgresIntegrationTest {

    @Autowired private PosSaleIdempotencyRepository repo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private IamTestData testData;

    private Long companyId;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Idem IT Org"));
        Company company  = companies.save(new Company(org, "IDEM", "Idem IT Co"));
        companyId = company.getId();
    }

    @AfterEach
    void tearDown() {
        testData.clearAll();
    }

    private int reserve(String key) {
        return txTemplate.execute(s -> repo.tryReserve(companyId, key));
    }

    @Test
    void tryReserve_firstClaims_duplicateReturnsZero_differentKeyClaims() {
        assertThat(reserve("KEY-1")).as("first claim").isEqualTo(1);
        assertThat(reserve("KEY-1")).as("duplicate of the same key").isEqualTo(0);
        assertThat(reserve("KEY-2")).as("a different key is its own claim").isEqualTo(1);
    }

    @Test
    void stampInvoiceUid_isReadableBack() {
        assertThat(reserve("KEY-3")).isEqualTo(1);

        txTemplate.executeWithoutResult(s -> repo.stampInvoiceUid(companyId, "KEY-3", "POSINV0000000000000000001"));

        var marker = repo.findByCompanyIdAndIdemKey(companyId, "KEY-3").orElseThrow();
        assertThat(marker.getInvoiceUid()).isEqualTo("POSINV0000000000000000001");
        assertThat(marker.getCreatedAt()).as("created_at defaulted by the DB").isNotNull();
    }

    @Test
    void sameKeyDifferentCompany_isNotADuplicate() {
        Organisation org2 = organisations.save(new Organisation("Idem IT Org 2"));
        Company company2  = companies.save(new Company(org2, "IDEM2", "Idem IT Co 2"));

        assertThat(reserve("SHARED")).as("company 1 claims").isEqualTo(1);
        int company2Claim = txTemplate.execute(s -> repo.tryReserve(company2.getId(), "SHARED"));
        assertThat(company2Claim).as("company 2 with the same key is independent").isEqualTo(1);
    }
}
