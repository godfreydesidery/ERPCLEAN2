package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression tests for error-message hygiene on the FX uid-resolution paths (PROBE 4 / standing
 * rule 2026-06-22): 404 messages must NOT echo back the caller-supplied uid or any internal
 * identifier — they must be generic and user-safe.
 *
 * <p>Manual repro (before fix):
 * <pre>
 *   POST /api/v1/fx/company-currencies/resolve-branch
 *   Body: { "branchUid": "01KW17R06HS3H7MKJC7M3YTWCC", "companyId": 1 }
 *   Response: 404 { "errors": ["Branch not found: 01KW17R06HS3H7MKJC7M3YTWCC"] }
 * </pre>
 * After fix the response must be: {@code {"errors":["The requested branch was not found."]}}.
 */
class CurrencyEnablementErrorMessageIT extends PostgresIntegrationTest {

    @Autowired private CurrencyEnablementService enablementService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private IamTestData               testData;

    private String companyAUid;
    private Long   companyAId;
    private String branchBUid;   // belongs to company B — foreign to company A

    @BeforeEach
    @Transactional
    void setUp() {
        testData.clearAll();

        Organisation org = new Organisation("Error-Message IT Org");
        organisations.save(org);

        // Company A — the "caller's" company
        Company companyA = new Company(org, "ERRA", "Error Test Co A");
        companies.save(companyA);
        companyAId  = companyA.getId();
        companyAUid = companyA.getUid();

        // Company B — a different tenant
        Company companyB = new Company(org, "ERRB", "Error Test Co B");
        companies.save(companyB);

        // Branch that belongs to company B only
        Branch branchB = new Branch(companyB, "BRBR", "Branch of B");
        branches.save(branchB);
        branchBUid = branchB.getUid();
    }

    @AfterEach
    void tearDown() {
        testData.clearAll();
    }

    // ── resolveCompanyId: unknown uid must not echo the uid ───────────────────

    @Test
    @Transactional
    void resolveCompanyId_unknownUid_messageIsGenericAndDoesNotLeakUid() {
        final String unknownUid = "01UNKNOWNCOMPANYUID000000XX";

        assertThatThrownBy(() -> enablementService.resolveCompanyId(unknownUid))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    // Must be the generic phrase
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .isEqualTo("The requested company was not found.");
                    // Must NOT contain the raw uid
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .doesNotContain(unknownUid);
                });
    }

    // ── resolveBranchId: foreign-company branch must not echo the uid ─────────

    /**
     * Simulates the PROBE 4 scenario: a caller supplies a branch uid that exists in the DB but
     * belongs to a different company. The service must return 404 (no existence leak) and the
     * message body must not contain the raw uid.
     */
    @Test
    @Transactional
    void resolveBranchId_branchFromForeignCompany_messageIsGenericAndDoesNotLeakUid() {
        assertThatThrownBy(() -> enablementService.resolveBranchId(branchBUid, companyAId))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .isEqualTo("The requested branch was not found.");
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .doesNotContain(branchBUid);
                });
    }

    @Test
    @Transactional
    void resolveBranchId_completelyUnknownUid_messageIsGenericAndDoesNotLeakUid() {
        final String unknownUid = "01UNKNOWNBRANCHUID000000XXX";

        assertThatThrownBy(() -> enablementService.resolveBranchId(unknownUid, companyAId))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .isEqualTo("The requested branch was not found.");
                    org.assertj.core.api.Assertions.assertThat(msg)
                            .doesNotContain(unknownUid);
                });
    }

    // ── resolveCompanyId: own company resolves successfully (sanity) ──────────

    @Test
    @Transactional
    void resolveCompanyId_knownUid_returnsId() {
        Long resolved = enablementService.resolveCompanyId(companyAUid);
        org.assertj.core.api.Assertions.assertThat(resolved).isEqualTo(companyAId);
    }
}
