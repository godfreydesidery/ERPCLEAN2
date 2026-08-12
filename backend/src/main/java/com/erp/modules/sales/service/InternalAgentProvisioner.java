package com.erp.modules.sales.service;

import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.service.InternalAgentUniquenessGuard;
import com.erp.modules.parties.service.PartyCodeGenerator;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the acting user's own internal sales agent, creating it on first use.
 *
 * <p><b>Why this exists.</b> Every sales invoice must name exactly one sales agent — the rule is
 * enforced all the way down to a NOT NULL column, so it cannot simply be relaxed, and it should not
 * be: a sale nobody made is not a sale anyone can be held to. But the only two ways to satisfy it
 * both assumed the agent master was already populated: name an agent explicitly, or be linked to
 * one. On a company where no agent has ever been created, the first is impossible (there is nothing
 * to name, and a cashier cannot see the agent list anyway) and the second is impossible (nobody is
 * linked). The rejection told the user to "select a sales agent, or ask an administrator" — pointing
 * at a door that no one could open. Nothing could be sold.
 *
 * <p><b>What it does instead.</b> The staff member ringing the sale <em>is</em> the sales agent for
 * it, so the missing master row is created for them, once, at the moment it is first needed —
 * provisioning rather than a data migration, the same shape as the tax-rate and sales-settings
 * seeders. The business rule is untouched: the sale still carries exactly one agent, and it is the
 * person who actually made it — a more truthful attribution than any fallback would give. An
 * administrator can rename, retarget or archive the row afterwards like any other agent.
 *
 * <p><b>Who does NOT get one.</b> Only an ACTIVE, non-root user who belongs to the company (the same
 * {@code isActiveUserInCompany} test the agent master itself applies on create, BR-PARTY-10). Root
 * is a system account, never a salesperson, so a root-driven sale still gets the explicit rejection
 * — which is what the existing behaviour intends and what the UAT scripts pin.
 *
 * <p><b>Provisioning happens once, and archiving still stops the person selling.</b> "Create it on
 * first use" is not "create it whenever one is missing": a user whose internal agent exists but is
 * not ACTIVE has been taken off sales deliberately, and gets a refusal telling them to ask an
 * administrator to reactivate it — never a fresh row, which would silently reverse the decision.
 * Before minting, the same at-most-one-active-internal-agent predicate the agent master enforces
 * ({@link com.erp.modules.parties.service.InternalAgentUniquenessGuard}) is consulted, so a
 * concurrent first sale is reused rather than duplicated.
 */
@Component
public class InternalAgentProvisioner {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentProvisioner.class);

    private final AgentRepository agents;
    private final PartyCodeGenerator codeGen;
    private final UserLookupService userLookup;
    private final AuditService audit;
    private final InternalAgentUniquenessGuard uniqueness;

    public InternalAgentProvisioner(AgentRepository agents,
                                    PartyCodeGenerator codeGen,
                                    UserLookupService userLookup,
                                    AuditService audit,
                                    InternalAgentUniquenessGuard uniqueness) {
        this.agents = agents;
        this.codeGen = codeGen;
        this.userLookup = userLookup;
        this.audit = audit;
        this.uniqueness = uniqueness;
    }

    /**
     * The acting user's ACTIVE internal agent in {@code companyId}, provisioning it if absent.
     *
     * <p>Runs in the caller's transaction (REQUIRED) so the new agent commits with the sale, or
     * rolls back with it — a refused sale never leaves a stray agent behind.
     *
     * @return the agent id, or empty when the caller cannot hold an internal agent at all (no
     *         principal, or not an active non-root member of this company) — the caller then raises
     *         its own domain refusal
     * @throws IllegalArgumentException when the caller's internal agent exists but is not ACTIVE:
     *         an administrator took them off sales, and that has to stop the sale rather than mint
     *         a replacement. Distinct from the empty result because the remedy is different
     *         (reactivate this agent, not link one).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<Long> resolveOrProvision(Long companyId, RequestContext.Principal ctx) {
        if (companyId == null || ctx == null || ctx.userId() == null) {
            return Optional.empty();
        }
        Optional<Long> existing =
                agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
        if (existing.isPresent()) {
            return existing;
        }
        // The off-switch. An agent that exists but is not ACTIVE was archived (or deactivated) by an
        // administrator ON PURPOSE — that is how a person is taken off sales. Minting a replacement
        // here would undo the decision on the very next sale and leave the administrator staring at
        // an archived row that changed nothing. Refuse instead, and say who can undo it.
        if (agents.existsByCompanyIdAndAppUserIdAndAgentKindAndStatusNot(
                companyId, ctx.userId(), AgentKind.INTERNAL, MasterStatus.ACTIVE)) {
            log.warn("Sale refused: userId={} has a non-active internal agent in companyId={}; "
                    + "no replacement is provisioned.", ctx.userId(), companyId);
            throw new IllegalArgumentException(
                    "Your sales agent record is no longer active, so sales cannot be recorded "
                    + "under it. Ask an administrator to reactivate your sales agent.");
        }
        // Same eligibility test the agent master applies at create time (BR-PARTY-10): ACTIVE,
        // not root, and staff of THIS company. Provisioning must never invent an agent for someone
        // the admin screens would have refused to link.
        if (!userLookup.isActiveUserInCompany(ctx.userId(), companyId)) {
            log.warn("Internal agent not provisioned: userId={} is not an active non-root member "
                    + "of companyId={}", ctx.userId(), companyId);
            return Optional.empty();
        }
        // Last look before minting, through the SAME predicate the agent master enforces on create
        // (a user backs at most one active internal agent) rather than around it. A concurrent first
        // sale that has already committed its row is picked up here and reused: a second AGNT- code
        // for one person is master-data churn nobody can tidy up afterwards.
        if (uniqueness.hasActiveInternalAgent(companyId, ctx.userId())) {
            log.info("Internal agent for userId={} in companyId={} appeared concurrently; reusing it "
                    + "instead of provisioning a second one.", ctx.userId(), companyId);
            return agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
        }

        String code = codeGen.next(companyId, "AGENT");
        Agent agent = new Agent(companyId, code, PartyType.INDIVIDUAL, displayNameFor(ctx),
                AgentKind.INTERNAL, ctx.userId());
        agent.setAppUserId(ctx.userId());
        Agent saved = agents.save(agent);

        Map<String, Object> detail = new HashMap<>();
        detail.put("code", saved.getCode());
        detail.put("agentKind", AgentKind.INTERNAL.name());
        detail.put("appUserId", String.valueOf(ctx.userId()));
        // Distinguishes a self-provisioned row from one an administrator deliberately created, so
        // "where did this agent come from?" is answerable from the trail alone.
        detail.put("provisionedOnFirstSale", "true");
        audit.record(AuditEvent.of(AuditActions.AGENT_CREATE, "agents",
                saved.getId(), saved.getUid()).detail(detail));

        log.info("Provisioned internal sales agent {} for userId={} in companyId={} on first sale.",
                saved.getCode(), ctx.userId(), companyId);
        return Optional.of(saved.getId());
    }

    /**
     * Best available human name for the new agent: the user's display name, falling back to their
     * username. Never blank — {@code display_name} is NOT NULL and an agent nobody can recognise on
     * a report is barely better than no agent at all.
     */
    private String displayNameFor(RequestContext.Principal ctx) {
        String displayName = userLookup.displayNamesByIds(List.of(ctx.userId()))
                .get(ctx.userId());
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return ctx.username() != null && !ctx.username().isBlank()
                ? ctx.username()
                : "Sales agent";
    }
}
