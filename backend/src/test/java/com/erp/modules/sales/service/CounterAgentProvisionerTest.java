package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** LSF-04: the company's Counter agent is created once, reused, and never replaced once archived. */
class CounterAgentProvisionerTest {

    private static final Long COMPANY_ID = 1L;

    private final AgentRepository agents = mock(AgentRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final CounterAgentProvisioner provisioner = new CounterAgentProvisioner(agents, audit);

    @Test
    void createsTheCounterAgentOnFirstUse() {
        when(agents.findByCompanyIdAndCode(COMPANY_ID, "COUNTER")).thenReturn(Optional.empty());
        when(agents.save(any())).thenAnswer(a -> withId(a.getArgument(0), 700L));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, 1L)).contains(700L);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agents).save(captor.capture());
        Agent saved = captor.getValue();
        assertThat(saved.getCode()).isEqualTo("COUNTER");
        assertThat(saved.getDisplayName()).isEqualTo("Counter");
        // EXTERNAL with no user link: chk_agent_user_kind forbids an INTERNAL agent without one,
        // and root (the only actor here) may not be linked to an agent.
        assertThat(saved.getAgentKind()).isEqualTo(AgentKind.EXTERNAL);
        assertThat(saved.getAppUserId()).isNull();
        verify(audit).record(any());
    }

    @Test
    void reusesTheExistingActiveCounterAgent() {
        Agent existing = withId(counter(), 701L);
        when(agents.findByCompanyIdAndCode(COMPANY_ID, "COUNTER")).thenReturn(Optional.of(existing));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, 1L)).contains(701L);
        verify(agents, never()).save(any());
    }

    @Test
    void anArchivedCounterAgentIsNeverReplaced() {
        Agent archived = withId(counter(), 702L);
        archived.setStatus(MasterStatus.ARCHIVED);
        when(agents.findByCompanyIdAndCode(COMPANY_ID, "COUNTER")).thenReturn(Optional.of(archived));

        assertThat(provisioner.resolveOrProvision(COMPANY_ID, 1L)).isEmpty();
        verify(agents, never()).save(any());
    }

    private static Agent counter() {
        return new Agent(COMPANY_ID, "COUNTER", PartyType.INDIVIDUAL, "Counter",
                AgentKind.EXTERNAL, 1L);
    }

    private static Agent withId(Agent agent, Long id) {
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }
}
