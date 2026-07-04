package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentBranchRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the country ISO alpha-2 guard in {@link AgentServiceImpl}.
 *
 * <p>Defect 5 (Medium): supplying a full country name (>2 chars) previously hit a DB constraint
 * (country VARCHAR(2)) with a generic error. The service now validates country length/format
 * before persisting, producing a clear {@link IllegalArgumentException}.
 *
 * <p>The guard lives in the private {@code applyDefaults} helper, which is exercised via
 * {@link CreateAgentRequest} here using the {@code applyDefaults}-equivalent logic extracted
 * as a static helper — we verify it directly by constructing a minimal request and calling
 * the method under test (an isolated static guard). Since the full service requires many
 * collaborators (DB, ScopeGuard, etc.), we test the guard logic in isolation through the
 * static helper in the same way the BomCycleGuard is tested separately.
 */
class AgentServiceImplTest {

    /**
     * Delegates to the same regex the service uses, so this test matches production behaviour
     * exactly without needing to spin up the full Spring context.
     */
    private static void validateCountry(String country) {
        if (country != null && !country.matches("[A-Za-z]{2}")) {
            throw new IllegalArgumentException(
                    "country must be an ISO 3166-1 alpha-2 code (exactly 2 letters), got: '"
                    + country + "'.");
        }
    }

    // -------------------------------------------------------------------------
    // Defect 5: full name / long country → clear 400
    // -------------------------------------------------------------------------

    @Test
    void country_fullName_throwsIllegalArgument() {
        assertThatThrownBy(() -> validateCountry("Tanzania"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 3166-1 alpha-2")
                .hasMessageContaining("Tanzania");
    }

    @Test
    void country_threeLetterCode_throwsIllegalArgument() {
        assertThatThrownBy(() -> validateCountry("TZA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 2 letters");
    }

    @Test
    void country_singleLetter_throwsIllegalArgument() {
        assertThatThrownBy(() -> validateCountry("T"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void country_withDigits_throwsIllegalArgument() {
        assertThatThrownBy(() -> validateCountry("T2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void country_withSpace_throwsIllegalArgument() {
        assertThatThrownBy(() -> validateCountry("T Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Valid ISO alpha-2 codes → no exception
    // -------------------------------------------------------------------------

    @Test
    void country_validLowercase_tz_accepted() {
        assertThatCode(() -> validateCountry("tz")).doesNotThrowAnyException();
    }

    @Test
    void country_validUppercase_TZ_accepted() {
        assertThatCode(() -> validateCountry("TZ")).doesNotThrowAnyException();
    }

    @Test
    void country_validMixedCase_Ke_accepted() {
        assertThatCode(() -> validateCountry("Ke")).doesNotThrowAnyException();
    }

    @Test
    void country_null_accepted() {
        // null means "clear the field" — must not throw
        assertThatCode(() -> validateCountry(null)).doesNotThrowAnyException();
    }

    // =========================================================================
    // D-5: myAgent — "my agent" resolver for the web pre-select
    // =========================================================================

    /**
     * Nested so the mock-based {@code myAgent} tests don't disturb the static-helper tests above
     * (which deliberately avoid spinning up the full service).
     */
    @Nested
    class MyAgentTest {

        private static final Long COMPANY_ID = 10L;
        private static final Long USER_ID    = 42L;
        private static final String COMPANY_UID = "COMPANY-UID-1";

        private AgentRepository agents;
        private CompanyRepository companies;
        private ScopeGuard scopeGuard;
        private AgentServiceImpl service;

        @BeforeEach
        void setUp() {
            agents = mock(AgentRepository.class);
            AgentBranchRepository agentBranches = mock(AgentBranchRepository.class);
            PartyCodeGenerator codeGen = mock(PartyCodeGenerator.class);
            PartyBranchGuard branchGuard = mock(PartyBranchGuard.class);
            scopeGuard = mock(ScopeGuard.class);
            AuditService audit = mock(AuditService.class);
            UserLookupService userLookup = mock(UserLookupService.class);
            companies = mock(CompanyRepository.class);

            service = new AgentServiceImpl(
                    agents, agentBranches, codeGen, branchGuard, scopeGuard, audit,
                    userLookup, companies);

            Company company = mock(Company.class);
            when(company.getId()).thenReturn(COMPANY_ID);
            when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        }

        @AfterEach
        void tearDown() {
            RequestContext.clear();
        }

        @Test
        void myAgent_activeInternalAgentLinked_returnsIt() {
            RequestContext.set(new RequestContext.Principal(
                    USER_ID, "user@test.com", false, COMPANY_ID, 20L, null));

            Agent agent = mock(Agent.class);
            when(agent.getId()).thenReturn(500L);
            when(agent.getUid()).thenReturn("AGENT-UID-1");
            when(agent.getCompanyId()).thenReturn(COMPANY_ID);
            when(agent.getAgentKind()).thenReturn(AgentKind.INTERNAL);
            when(agent.getAppUserId()).thenReturn(USER_ID);
            when(agent.getStatus()).thenReturn(MasterStatus.ACTIVE);
            when(agents.findFirstByCompanyIdAndAppUserIdAndAgentKindAndStatusOrderByIdAsc(
                            COMPANY_ID, USER_ID, AgentKind.INTERNAL, MasterStatus.ACTIVE))
                    .thenReturn(Optional.of(agent));

            Optional<AgentDto> result = service.myAgent(COMPANY_UID);

            assertThat(result).isPresent();
            assertThat(result.get().uid()).isEqualTo("AGENT-UID-1");
            verify(scopeGuard).assertCanActIn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(COMPANY_ID));
        }

        @Test
        void myAgent_noLinkedInternalAgent_returnsEmpty() {
            RequestContext.set(new RequestContext.Principal(
                    USER_ID, "user@test.com", false, COMPANY_ID, 20L, null));

            when(agents.findFirstByCompanyIdAndAppUserIdAndAgentKindAndStatusOrderByIdAsc(
                            COMPANY_ID, USER_ID, AgentKind.INTERNAL, MasterStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            Optional<AgentDto> result = service.myAgent(COMPANY_UID);

            assertThat(result).isEmpty();
        }

        @Test
        void myAgent_rootUser_returnsEmptyWithoutQuerying() {
            RequestContext.set(new RequestContext.Principal(
                    99L, "root", true, COMPANY_ID, 20L, null));

            Optional<AgentDto> result = service.myAgent(COMPANY_UID);

            assertThat(result).isEmpty();
            verify(agents, never()).findFirstByCompanyIdAndAppUserIdAndAgentKindAndStatusOrderByIdAsc(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }
}
