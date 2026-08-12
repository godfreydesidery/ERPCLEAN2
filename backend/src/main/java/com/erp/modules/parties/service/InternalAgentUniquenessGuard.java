package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import org.springframework.stereotype.Component;

/**
 * One owner for the rule "a user backs at most ONE ACTIVE internal sales agent".
 *
 * <p>The rule was previously a private method on {@link AgentServiceImpl}, so it bound only the
 * admin screens. Anything else that creates an internal agent — notably the sale-time provisioner —
 * could mint a second row for the same user behind its back, and a duplicate makes sale-time agent
 * resolution ambiguous (it once surfaced as a bare 500 on every sale). Extracted here so both
 * writers consult the same predicate.
 *
 * <p><b>Limit.</b> This is an app-level check, not a constraint: there is no DB uniqueness on
 * {@code (company_id, app_user_id, agent_kind)}, so two transactions that overlap entirely can still
 * both pass it. It closes the far wider window where one write simply never asked. The durable fix
 * is a partial unique index, which needs its own migration.
 */
@Component
public class InternalAgentUniquenessGuard {

    private final AgentRepository agents;

    public InternalAgentUniquenessGuard(AgentRepository agents) {
        this.agents = agents;
    }

    /** True when {@code appUserId} already backs an ACTIVE internal agent in this company. */
    public boolean hasActiveInternalAgent(Long companyId, Long appUserId) {
        return hasOtherActiveInternalAgent(companyId, appUserId, null);
    }

    /**
     * As {@link #hasActiveInternalAgent} but ignoring one row — the record being updated or
     * restored, which must not collide with itself.
     */
    public boolean hasOtherActiveInternalAgent(Long companyId, Long appUserId, Long selfId) {
        if (companyId == null || appUserId == null) {
            return false;
        }
        return selfId == null
                ? agents.existsByCompanyIdAndAppUserIdAndAgentKindAndStatus(
                        companyId, appUserId, AgentKind.INTERNAL, MasterStatus.ACTIVE)
                : agents.existsByCompanyIdAndAppUserIdAndAgentKindAndStatusAndIdNot(
                        companyId, appUserId, AgentKind.INTERNAL, MasterStatus.ACTIVE, selfId);
    }

    /**
     * Write-time enforcement for the agent master: a friendly 409 rather than a duplicate that only
     * shows up later, when a sale cannot decide which agent is the user's.
     *
     * @param selfId the row being changed (update/restore), excluded from the check; {@code null}
     *               on create.
     */
    public void assertNoOtherActiveInternalAgent(Long companyId, Long appUserId, Long selfId) {
        if (hasOtherActiveInternalAgent(companyId, appUserId, selfId)) {
            throw new ConflictException(
                    "This user is already linked to an active internal sales agent. "
                    + "A user can have only one internal sales agent.");
        }
    }
}
