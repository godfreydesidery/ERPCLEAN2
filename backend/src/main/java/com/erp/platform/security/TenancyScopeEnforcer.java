package com.erp.platform.security;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place a tenant boundary is asserted (ADR-0062, MULTITENANCY-PLAN.md P2.5-1).
 *
 * <p>Sibling of {@link ScopeGuard}, which confines a caller to a company. This one confines them to
 * an <b>organisation</b>. Every organisation comparison goes through here so the exemption below
 * cannot be forgotten at one site out of many, and so "what is exempt from tenancy checks" is
 * answerable by reading one class.
 *
 * <h2>The exemption, and why it keys on the user and not the organisation</h2>
 *
 * A SYSTEM principal is exempt. It is identified by a <b>null {@code userId}</b> — see
 * {@link RequestContext.Principal#system()}. The eighteen outbox handlers install exactly that when
 * they replay a committed event, because they are not acting for a tenant: the event's company is
 * already fixed, and denying them would fail an asynchronous GL or stock posting that nobody is
 * watching.
 *
 * <p>An earlier draft of the plan said to exempt a principal whose <b>organisation</b> is null.
 * That would have been a privilege-escalation hole. A real user can legitimately have a null
 * organisation — every account created between V101 and the constraining migration does — so
 * exempting there would have granted those users unscoped sessions through a <i>data gap</i> rather
 * than an authorisation decision, and the newer the account the more privileged it would have been.
 * A null {@code userId} cannot be produced by missing data and cannot be reached from a request:
 * the request filter always sets one from the authenticated subject.
 *
 * <h2>Transitional null handling</h2>
 *
 * Two null organisations compare equal and are allowed. That is deliberate and temporary: until the
 * constraining migration lands, a legacy row and a row created before P2-1 can both be null, and
 * refusing there would break working installations for no security gain — at one organisation there
 * is nothing to cross. A null on one side and a value on the other is a genuine mismatch and is
 * refused. Once the column is NOT NULL the case disappears on its own.
 */
@Component
public class TenancyScopeEnforcer {

    private static final Logger log = LoggerFactory.getLogger(TenancyScopeEnforcer.class);

    /**
     * True when {@code target} is inside the caller's tenant, or the caller is SYSTEM.
     *
     * @param caller             the acting principal; a null principal is treated as SYSTEM, matching
     *                           how scheduled and async threads run with no request context
     * @param targetOrganisationId the organisation of the thing being touched, taken from the LOADED
     *                           row — never from a request parameter, or the check is circular
     */
    public boolean isSameTenant(RequestContext.Principal caller, Long targetOrganisationId) {
        if (caller == null || caller.system()) {
            return true;
        }
        return Objects.equals(caller.organisationId(), targetOrganisationId);
    }

    /**
     * Refuses unless {@code target} is inside the caller's tenant.
     *
     * <p>The message deliberately says nothing about organisations. Telling a caller that a thing
     * exists but belongs to someone else is an existence oracle across a tenant boundary; the
     * refusal must be indistinguishable from "not found here".
     *
     * @param what a short noun for the log line only — never rendered to the caller
     */
    public void assertSameTenant(RequestContext.Principal caller, Long targetOrganisationId, String what) {
        if (isSameTenant(caller, targetOrganisationId)) {
            return;
        }
        // Logged at WARN with both sides: on a shared instance this is either an attack or a real
        // isolation defect, and both need to be visible without being guessable by the caller.
        log.warn("Cross-tenant {} refused: caller user={} org={} target org={}",
                what, caller.userId(), caller.organisationId(), targetOrganisationId);
        throw new org.springframework.security.access.AccessDeniedException("Not permitted.");
    }
}
