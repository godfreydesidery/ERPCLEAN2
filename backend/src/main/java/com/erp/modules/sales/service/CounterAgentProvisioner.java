package com.erp.modules.sales.service;

import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's "Counter" sales agent, created on first use (LSF-04 / LUI-06).
 *
 * <p><b>Why.</b> Every invoice must name one sales agent (BR-SALES-06; {@code agent_id} is NOT
 * NULL). Staff get their own internal agent provisioned on their first sale
 * ({@link InternalAgentProvisioner}), but the system administrator cannot hold one: root is a system
 * account, the agent master refuses to link it, and so on a fresh install the owner — signed in as
 * root — could not ring the very first sale. The counter agent is what such a sale is attributed to.
 *
 * <p><b>What it is.</b> An EXTERNAL agent (no user link: {@code chk_agent_user_kind} requires an
 * INTERNAL agent to reference a user, and the only user here is root, whom the agent master refuses)
 * with the reserved, company-unique code {@value #COUNTER_CODE} and the display name "Counter". The
 * code is how it is found again, so provisioning is idempotent without any new column: the
 * {@code (company_id, code)} unique constraint already guarantees there is at most one.
 *
 * <p><b>Off-switch.</b> An administrator may rename it or archive it like any other agent. An
 * archived counter agent is never replaced: the sale falls back to the original refusal, so the
 * decision to stop counter attribution sticks.
 */
@Component
public class CounterAgentProvisioner {

    /** Reserved agent code for the company's counter agent. */
    public static final String COUNTER_CODE = "COUNTER";

    /** Display name given on creation; administrators may rename it. */
    static final String COUNTER_NAME = "Counter";

    private static final Logger log = LoggerFactory.getLogger(CounterAgentProvisioner.class);

    private final AgentRepository agents;
    private final AuditService audit;

    public CounterAgentProvisioner(AgentRepository agents, AuditService audit) {
        this.agents = agents;
        this.audit = audit;
    }

    /**
     * The company's ACTIVE counter agent id, creating it when the company has none. Runs in the
     * caller's transaction, so a refused sale never leaves a stray agent behind.
     *
     * @return the agent id, or empty when the counter agent exists but has been taken out of use
     *         (not ACTIVE) — the caller then raises its own refusal
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<Long> resolveOrProvision(Long companyId, Long actorId) {
        if (companyId == null) {
            return Optional.empty();
        }
        Optional<Agent> existing = agents.findByCompanyIdAndCode(companyId, COUNTER_CODE);
        if (existing.isPresent()) {
            Agent agent = existing.get();
            if (agent.getStatus() == MasterStatus.ACTIVE) {
                return Optional.of(agent.getId());
            }
            log.warn("Counter agent in companyId={} is {}; not replaced, sale falls back to the "
                    + "agent-required refusal.", companyId, agent.getStatus());
            return Optional.empty();
        }

        Agent agent = new Agent(companyId, COUNTER_CODE, PartyType.INDIVIDUAL, COUNTER_NAME,
                AgentKind.EXTERNAL, actorId);
        Agent saved = agents.save(agent);

        Map<String, Object> detail = new HashMap<>();
        detail.put("code", saved.getCode());
        detail.put("agentKind", AgentKind.EXTERNAL.name());
        // Distinguishes this row from one an administrator created, so "where did the Counter
        // agent come from?" is answerable from the trail alone.
        detail.put("provisionedAsCounterAgent", "true");
        audit.record(AuditEvent.of(AuditActions.AGENT_CREATE, "agents",
                saved.getId(), saved.getUid()).detail(detail));

        log.info("Provisioned counter sales agent for companyId={} on first sale.", companyId);
        return Optional.of(saved.getId());
    }
}
