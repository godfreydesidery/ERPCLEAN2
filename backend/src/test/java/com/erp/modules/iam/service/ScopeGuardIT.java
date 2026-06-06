package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link ScopeGuard} against real Postgres (ADR-0002).
 *
 * <p>Covers: root always allowed; non-root principal scoped to company A may act on company A's
 * entities but not company B's; unknown/garbage uid returns false; assertCanActIn throws 403 for
 * out-of-scope company.
 */
class ScopeGuardIT extends PostgresIntegrationTest {

    @Autowired private ScopeGuard scopeGuard;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private IamTestData testData;

    private Company companyA;
    private Company companyB;
    private Branch branchInA;
    private Branch branchInB;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Scope Corp"));
        companyA = companies.save(new Company(org, "CA", "Company A"));
        companyB = companies.save(new Company(org, "CB", "Company B"));
        branchInA = branches.save(new Branch(companyA, "A1", "Branch A1"));
        branchInB = branches.save(new Branch(companyB, "B1", "Branch B1"));
    }

    // ---------------------------------------------------------------
    // root principal -> canActOn anything is true
    // ---------------------------------------------------------------

    @Test
    void canActOn_rootPrincipal_alwaysTrue() {
        var root = new RequestContext.Principal(1L, "root", true, null, null, null);

        assertThat(scopeGuard.canActOn(root, "company", companyA.getUid())).isTrue();
        assertThat(scopeGuard.canActOn(root, "company", companyB.getUid())).isTrue();
        assertThat(scopeGuard.canActOn(root, "branch", branchInA.getUid())).isTrue();
        assertThat(scopeGuard.canActOn(root, "branch", branchInB.getUid())).isTrue();
        assertThat(scopeGuard.canActOn(root, "company", "GARBAGE_UID_DOES_NOT_EXIST")).isTrue();
    }

    // ---------------------------------------------------------------
    // non-root principal active in company A
    // ---------------------------------------------------------------

    @Test
    void canActOn_nonRoot_ownCompanyUid_isTrue() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActOn(principal, "company", companyA.getUid())).isTrue();
    }

    @Test
    void canActOn_nonRoot_otherCompanyUid_isFalse() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActOn(principal, "company", companyB.getUid())).isFalse();
    }

    @Test
    void canActOn_nonRoot_branchInOwnCompany_isTrue() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActOn(principal, "branch", branchInA.getUid())).isTrue();
    }

    @Test
    void canActOn_nonRoot_branchInOtherCompany_isFalse() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActOn(principal, "branch", branchInB.getUid())).isFalse();
    }

    @Test
    void canActOn_nonRoot_unknownUid_isFalse() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActOn(principal, "company", "01HZZZZZZZZZZZZZZZZZZZZZZY")).isFalse();
        assertThat(scopeGuard.canActOn(principal, "branch",  "01HZZZZZZZZZZZZZZZZZZZZZZY")).isFalse();
    }

    @Test
    void canActOn_nullPrincipal_isFalse() {
        assertThat(scopeGuard.canActOn(null, "company", companyA.getUid())).isFalse();
    }

    // ---------------------------------------------------------------
    // assertCanActIn throws ForbiddenException for out-of-scope company
    // ---------------------------------------------------------------

    @Test
    void assertCanActIn_ownCompany_doesNotThrow() {
        var principal = principalFor(companyA);
        scopeGuard.assertCanActIn(principal, companyA.getId()); // must not throw
    }

    @Test
    void assertCanActIn_otherCompany_throwsForbidden() {
        var principal = principalFor(companyA);
        assertThatThrownBy(() -> scopeGuard.assertCanActIn(principal, companyB.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void assertCanActIn_nullPrincipal_throwsForbidden() {
        assertThatThrownBy(() -> scopeGuard.assertCanActIn(null, companyA.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---------------------------------------------------------------
    // canActIn: canActIn and companyIdOf basics
    // ---------------------------------------------------------------

    @Test
    void canActIn_root_isAlwaysTrue() {
        var root = new RequestContext.Principal(1L, "root", true, null, null, null);
        assertThat(scopeGuard.canActIn(root, companyA.getId())).isTrue();
        assertThat(scopeGuard.canActIn(root, companyB.getId())).isTrue();
    }

    @Test
    void canActIn_nonRoot_matchingCompanyId_isTrue() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActIn(principal, companyA.getId())).isTrue();
    }

    @Test
    void canActIn_nonRoot_differentCompanyId_isFalse() {
        var principal = principalFor(companyA);
        assertThat(scopeGuard.canActIn(principal, companyB.getId())).isFalse();
    }

    // ---------------------------------------------------------------
    // companyIdOf resolves to correct company id per type
    // ---------------------------------------------------------------

    @Test
    void companyIdOf_companyType_resolvesToCompanyId() {
        assertThat(scopeGuard.companyIdOf("company", companyA.getUid()))
                .isPresent()
                .hasValue(companyA.getId());
    }

    @Test
    void companyIdOf_branchType_resolvesToBranchOwningCompanyId() {
        assertThat(scopeGuard.companyIdOf("branch", branchInA.getUid()))
                .isPresent()
                .hasValue(companyA.getId());
    }

    @Test
    void companyIdOf_unknownType_isEmpty() {
        assertThat(scopeGuard.companyIdOf("organisation", companyA.getUid())).isEmpty();
        assertThat(scopeGuard.companyIdOf("garbage", companyA.getUid())).isEmpty();
    }

    @Test
    void companyIdOf_nullUid_isEmpty() {
        assertThat(scopeGuard.companyIdOf("company", null)).isEmpty();
    }

    // --- private helpers ---

    private static RequestContext.Principal principalFor(Company company) {
        return new RequestContext.Principal(99L, "nonroot", false, company.getId(), null, null);
    }
}
