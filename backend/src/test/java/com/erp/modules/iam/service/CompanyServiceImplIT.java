package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CompanyServiceImplIT extends PostgresIntegrationTest {

    @Autowired private CompanyService companyService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private IamTestData testData;

    private String orgUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Acme Group"));
        this.orgUid = org.getUid();
    }

    @Test
    void create_thenGet_roundTrips() {
        CompanyDto created = companyService.create(
                new CreateCompanyRequest(orgUid, "C1", "Acme Tanzania", "Acme Ltd", "TIN-123", null));
        assertThat(created.uid()).isNotBlank();
        assertThat(created.code()).isEqualTo("C1");

        CompanyDto fetched = companyService.getByUid(created.uid());
        assertThat(fetched.name()).isEqualTo("Acme Tanzania");
        assertThat(fetched.organisationId()).isEqualTo(created.organisationId());
    }

    @Test
    void create_duplicateCodeInOrganisation_isRejected() {
        companyService.create(new CreateCompanyRequest(orgUid, "C1", "First", null, null, null));
        assertThatThrownBy(() ->
                companyService.create(new CreateCompanyRequest(orgUid, "C1", "Dup", null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void update_changesMutableFields() {
        CompanyDto c = companyService.create(
                new CreateCompanyRequest(orgUid, "C1", "Old", null, null, null));
        CompanyDto updated = companyService.updateByUid(
                c.uid(), new UpdateCompanyRequest("New Name", "New Legal", "TIN-9", "Africa/Nairobi", null));
        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.timeZone()).isEqualTo("Africa/Nairobi");
    }

    @Test
    void getByUid_unknown_throwsNotFound() {
        assertThatThrownBy(() -> companyService.getByUid("NONEXISTENTUID0000000000000"))
                .isInstanceOf(NotFoundException.class);
    }
}
