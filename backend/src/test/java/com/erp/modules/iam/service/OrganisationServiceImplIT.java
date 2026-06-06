package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for organisation read access against real Postgres. Verifies the page can
 * resolve the deployment's single organisation by name (no uid typing), and that an empty DB yields
 * a clean 404-mapped error rather than a null.
 */
class OrganisationServiceImplIT extends PostgresIntegrationTest {

    @Autowired private OrganisationService organisationService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private IamTestData testData;

    @BeforeEach
    void setUp() {
        testData.clearAll();
    }

    @Test
    void current_returnsTheSingleOrganisation_withItsName() {
        organisations.save(new Organisation("Acme Group"));

        OrganisationDto dto = organisationService.current();

        assertThat(dto.name()).isEqualTo("Acme Group");
        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.id()).isNotNull();
    }

    @Test
    void current_whenNoOrganisationExists_throwsNotFound() {
        assertThatThrownBy(() -> organisationService.current())
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("organisation");
    }

    @Test
    void current_isDeterministic_whenMoreThanOneExists() {
        Organisation first = organisations.save(new Organisation("Alpha"));
        organisations.save(new Organisation("Beta"));

        // Earliest-by-id is the deterministic backstop (not whichever the DB returns first).
        assertThat(organisationService.current().id()).isEqualTo(first.getId());
    }

    @Test
    void list_returnsOrganisationsOrderedByName() {
        organisations.save(new Organisation("Beta"));
        organisations.save(new Organisation("Alpha"));

        assertThat(organisationService.list()).extracting(OrganisationDto::name)
                .containsExactly("Alpha", "Beta");
    }
}
