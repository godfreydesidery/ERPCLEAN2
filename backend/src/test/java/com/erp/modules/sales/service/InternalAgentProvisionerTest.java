package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.service.InternalAgentUniquenessGuard;
import com.erp.modules.parties.service.PartyCodeGenerator;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * UAT wave 1 — nothing could be sold because every sale demanded a sales agent and no company had
 * one. The rule is not the bug (a sale nobody made is not a sale anyone can be held to, and the
 * column is NOT NULL); the unreachable remedy was. These pin the replacement: the staff member
 * ringing the sale gets their own internal agent, created once, on first use.
 */
@ExtendWith(MockitoExtension.class)
class InternalAgentProvisionerTest {

    @Mock AgentRepository agents;
    @Mock PartyCodeGenerator codeGen;
    @Mock UserLookupService userLookup;
    @Mock AuditService audit;
    /** The agent master's own at-most-one-active-internal-agent rule, now shared with this class. */
    @Mock InternalAgentUniquenessGuard uniqueness;

    @InjectMocks InternalAgentProvisioner provisioner;

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID = 77L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void whenTheUserAlreadyHasAnInternalAgent_itIsReusedAndNothingIsCreated() {
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.of(300L));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, cashier())).contains(300L);

        verify(agents, never()).save(any());
    }

    @Test
    void whenTheCompanyHasNoAgentForTheUser_oneIsProvisionedForThemOnFirstSale() {
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(userLookup.isActiveUserInCompany(USER_ID, COMPANY_ID)).thenReturn(true);
        when(userLookup.displayNamesByIds(List.of(USER_ID))).thenReturn(Map.of(USER_ID, "Asha Mwita"));
        when(codeGen.next(COMPANY_ID, "AGENT")).thenReturn("AGNT-0001");
        when(agents.save(any())).thenAnswer(a -> withId(a.getArgument(0), 999L));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, cashier())).contains(999L);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agents).save(captor.capture());
        Agent created = captor.getValue();
        assertThat(created.getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(created.getCode()).isEqualTo("AGNT-0001");
        assertThat(created.getAgentKind()).isEqualTo(AgentKind.INTERNAL);
        assertThat(created.getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
        // Linked to the person who actually rang the sale — a truthful attribution, and the thing
        // that makes every agent-filtered sales report mean something.
        assertThat(created.getAppUserId()).isEqualTo(USER_ID);
        assertThat(created.getDisplayName()).isEqualTo("Asha Mwita");
        // Self-provisioned rows must be tellable apart from ones an administrator created.
        verify(audit).record(any());
    }

    @Test
    void whenTheUserHasNoDisplayName_theirUsernameNamesTheAgent() {
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(userLookup.isActiveUserInCompany(USER_ID, COMPANY_ID)).thenReturn(true);
        when(userLookup.displayNamesByIds(List.of(USER_ID))).thenReturn(Map.of());
        when(codeGen.next(COMPANY_ID, "AGENT")).thenReturn("AGNT-0002");
        when(agents.save(any())).thenAnswer(a -> withId(a.getArgument(0), 998L));

        provisioner.resolveOrProvision(COMPANY_ID, cashier());

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agents).save(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("cashier");
    }

    @Test
    void aUserWhoIsNotActiveCompanyStaff_getsNoAgent() {
        // Root, a disabled account, or someone from another company: the agent master would refuse
        // to link them (BR-PARTY-10), so provisioning must refuse too. The caller then raises its
        // own refusal rather than inventing an agent nobody vouched for.
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(userLookup.isActiveUserInCompany(USER_ID, COMPANY_ID)).thenReturn(false);

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, cashier())).isEmpty();

        verify(agents, never()).save(any());
    }

    @Test
    void withNoPrincipalAtAll_nothingIsProvisioned() {
        assertThat(provisioner.resolveOrProvision(COMPANY_ID, null)).isEmpty();

        verify(agents, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // The off-switch. "Create it on first use" must not mean "create it whenever one is missing":
    // an archived agent is how an administrator takes someone off sales, and it has to hold.
    // -------------------------------------------------------------------------

    @Test
    void whenTheUsersInternalAgentWasArchived_theSaleIsRefusedAndNoReplacementIsMinted() {
        // No ACTIVE agent (the lookup filters on ACTIVE) but a non-active one exists: an
        // administrator archived it on purpose. Minting AGNT-nnnn here would reverse that decision
        // on the next sale and leave the archive doing nothing at all.
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(agents.existsByCompanyIdAndAppUserIdAndAgentKindAndStatusNot(
                COMPANY_ID, USER_ID, AgentKind.INTERNAL, MasterStatus.ACTIVE)).thenReturn(true);

        RequestContext.Principal cashier = cashier();
        assertThatThrownBy(() -> provisioner.resolveOrProvision(COMPANY_ID, cashier))
                .isInstanceOf(IllegalArgumentException.class)
                // The remedy the operator can actually act on — reactivate THIS agent, not "link one".
                .hasMessageContaining("reactivate your sales agent");

        verify(agents, never()).save(any());
        // Refused before the code sequence is touched, so a blocked sale never burns an agent code.
        verify(codeGen, never()).next(any(), any());
    }

    @Test
    void whenAConcurrentFirstSaleAlreadyMintedTheAgent_itIsReusedNotDuplicated() {
        // Two first sales by one cashier race: the other transaction committed its row between our
        // lookup and the insert. The agent master's uniqueness rule catches it, and the sale carries
        // on with the row that exists — one person, one AGNT- code.
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty(), Optional.of(555L));
        when(userLookup.isActiveUserInCompany(USER_ID, COMPANY_ID)).thenReturn(true);
        when(uniqueness.hasActiveInternalAgent(COMPANY_ID, USER_ID)).thenReturn(true);

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, cashier())).contains(555L);

        verify(agents, never()).save(any());
    }

    @Test
    void aUserWithNoAgentAtAllStillGetsOne_theUnblockIsNotLostToTheNewGuards() {
        // Regression guard for the UAT unblock itself: neither the archived-agent refusal nor the
        // uniqueness consult may re-close the door on the ordinary first sale.
        when(agents.findInternalAgentIdByCompanyAndUser(COMPANY_ID, USER_ID))
                .thenReturn(Optional.empty());
        when(agents.existsByCompanyIdAndAppUserIdAndAgentKindAndStatusNot(
                COMPANY_ID, USER_ID, AgentKind.INTERNAL, MasterStatus.ACTIVE)).thenReturn(false);
        when(uniqueness.hasActiveInternalAgent(COMPANY_ID, USER_ID)).thenReturn(false);
        when(userLookup.isActiveUserInCompany(USER_ID, COMPANY_ID)).thenReturn(true);
        when(userLookup.displayNamesByIds(List.of(USER_ID))).thenReturn(Map.of(USER_ID, "Asha Mwita"));
        when(codeGen.next(COMPANY_ID, "AGENT")).thenReturn("AGNT-0003");
        when(agents.save(any())).thenAnswer(a -> withId(a.getArgument(0), 997L));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, cashier())).contains(997L);
    }

    /** Stands in for the DB assigning the identity column on save. */
    private static Agent withId(Agent agent, Long id) {
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }

    /** A NON-root principal — root is a system account and is never a salesperson. */
    private static RequestContext.Principal cashier() {
        return new RequestContext.Principal(USER_ID, "cashier", false, COMPANY_ID, 10L, "127.0.0.1");
    }
}
