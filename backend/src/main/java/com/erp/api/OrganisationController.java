package com.erp.api;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.service.OrganisationService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import com.erp.modules.iam.domain.dto.CreateTenantRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation read endpoints (ARCHITECTURE §7). The organisation is created by bootstrap, so this
 * is read-only. {@code /current} lets the web resolve the deployment's single organisation (by name)
 * instead of requiring an admin to paste a uid. Returns raw DTOs — {@code ApiResponseAdvice} wraps
 * them. Permission gate uses {@code @perm.has} (ADR-0002 Bug-1 fix).
 */
@RestController
@RequestMapping("/api/v1/organisations")
public class OrganisationController {

    private final OrganisationService organisations;

    public OrganisationController(OrganisationService organisations) {
        this.organisations = organisations;
    }

    @GetMapping
    // P3-10 (ADR-0062): was COMPANY.VIEW, borrowed because no ORG.* code existed. Safe to switch —
    // the web client has no caller for this endpoint at all (149 components inject
    // OrganisationService and every one of them calls current(), not list()).
    @PreAuthorize("@perm.has('ORG.VIEW')")
    public List<OrganisationDto> list() {
        return organisations.list();
    }

    // /current is the web shell's bootstrap call (which org am I in?) — any authenticated user may
    // read it; it carries no sensitive data and is needed before any permission-scoped screen loads.
    /**
     * Provisions a whole new tenant (ADR-0062 P5-2).
     *
     * <p>Gated on {@code ORG.CREATE}, which lives in the {@code platform} permission module — and
     * V102's narrowed CROSS JOIN keeps that module out of every tenant's {@code ORG_ADMIN}. So no
     * customer can reach this however much authority they hold inside their own organisation, which
     * is the whole point of R-2.
     *
     * <p>This is the write mapping P1-9's CI assertion was written to forbid. That assertion was
     * correct for its moment — while no tenant could be created, a write mapping here could only be
     * a mistake — and it now needs re-specifying as "no write mapping EXCEPT the platform-gated
     * create/suspend", rather than deleting.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('ORG.CREATE')")
    public OrganisationDto createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return organisations.createTenant(request);
    }

    /** Suspends a tenant — enforced at login, not per request (P5-3). */
    @PostMapping("/uid/{uid}/suspend")
    @PreAuthorize("@perm.has('ORG.SUSPEND')")
    public OrganisationDto suspend(@PathVariable String uid) {
        return organisations.setStatus(uid, false);
    }

    /** Restores a suspended tenant (P5-3). */
    @PostMapping("/uid/{uid}/resume")
    @PreAuthorize("@perm.has('ORG.SUSPEND')")
    public OrganisationDto resume(@PathVariable String uid) {
        return organisations.setStatus(uid, true);
    }

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public OrganisationDto current() {
        return organisations.current();
    }
}
