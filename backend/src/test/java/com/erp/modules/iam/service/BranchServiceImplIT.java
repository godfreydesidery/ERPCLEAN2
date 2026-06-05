package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.BranchDto;
import com.erp.modules.iam.domain.dto.CreateBranchRequest;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for the company-default-branch invariant (BR-2) against real Postgres — the
 * money tests for Slice 1. Verifies: set-default clears the old default; the partial unique index
 * is respected; duplicate branch codes within a company are rejected; the default branch can't be
 * archived while default.
 */
class BranchServiceImplIT extends PostgresIntegrationTest {

    @Autowired private BranchService branchService;
    @Autowired private BranchRepository branches;
    @Autowired private CompanyRepository companies;
    @Autowired private OrganisationRepository organisations;
    @Autowired private IamTestData testData;

    private String companyUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Acme Group"));
        Company company = companies.save(new Company(org, "C1", "Acme Tanzania"));
        this.companyUid = company.getUid();
    }

    @Test
    void create_withMakeDefault_marksBranchDefault() {
        BranchDto b = branchService.create(
                new CreateBranchRequest(companyUid, "BR-01", "Kariakoo", null, true));
        assertThat(b.isDefault()).isTrue();
    }

    @Test
    void setDefault_clearsThePreviousDefault() {
        BranchDto a = branchService.create(
                new CreateBranchRequest(companyUid, "BR-01", "Kariakoo", null, true));
        BranchDto b = branchService.create(
                new CreateBranchRequest(companyUid, "BR-02", "Mwanza", null, false));

        // Promote B → A must lose default.
        branchService.setDefault(b.uid());

        assertThat(branchService.getByUid(a.uid()).isDefault()).isFalse();
        assertThat(branchService.getByUid(b.uid()).isDefault()).isTrue();
        // The DB-level invariant: exactly one default for the company.
        Long companyId = companies.findByUid(companyUid).orElseThrow().getId();
        assertThat(branches.findByCompanyIdAndIsDefaultTrue(companyId)).isPresent();
    }

    @Test
    void create_duplicateCodeInSameCompany_isRejected() {
        branchService.create(new CreateBranchRequest(companyUid, "BR-01", "Kariakoo", null, false));
        assertThatThrownBy(() ->
                branchService.create(new CreateBranchRequest(companyUid, "BR-01", "Dup", null, false)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void archive_defaultBranch_isRejected() {
        BranchDto a = branchService.create(
                new CreateBranchRequest(companyUid, "BR-01", "Kariakoo", null, true));
        assertThatThrownBy(() -> branchService.archiveByUid(a.uid()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_inheritsCompanyTimeZone_whenNotProvided() {
        BranchDto b = branchService.create(
                new CreateBranchRequest(companyUid, "BR-01", "Kariakoo", null, false));
        assertThat(b.timeZone()).isEqualTo("Africa/Dar_es_Salaam");
    }
}
