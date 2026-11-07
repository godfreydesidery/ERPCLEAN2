package com.erp.api;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.domain.dto.PurgeTenantRequestDto;
import com.erp.modules.iam.domain.dto.TenantPurgeReportDto;
import com.erp.modules.iam.service.OrganisationService;
import com.erp.modules.iam.service.TenantDepartureService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
    private final TenantDepartureService departure;

    public OrganisationController(OrganisationService organisations,
                                  TenantDepartureService departure) {
        this.organisations = organisations;
        this.departure = departure;
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

    /**
     * Every tenant on the instance, for the platform console (MULTITENANCY-PLAN §14.2 R-2). Gated on
     * {@code ORG.CREATE}: only whoever may create tenants may list them.
     */
    @GetMapping("/tenants")
    @PreAuthorize("@perm.has('ORG.CREATE')")
    public List<com.erp.modules.iam.domain.dto.TenantSummaryDto> tenants() {
        return organisations.listTenants();
    }

    /**
     * Streams a zip of every row of one organisation (R-7). Platform operator only: gated on
     * {@code ORG.SUSPEND} (a {@code platform}-module code) and re-checked in the service.
     *
     * <p>Returns {@code void} and writes the response itself, so {@code ApiResponseAdvice} has no
     * body to wrap; a refusal is raised before the first byte and is an ordinary JSON error.
     */
    @PostMapping("/uid/{uid}/export")
    @PreAuthorize("@perm.has('ORG.SUSPEND')")
    public void export(@PathVariable String uid, HttpServletResponse response) throws IOException {
        departure.exportTenant(uid, fileName -> {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.setHeader("Cache-Control", "no-store");
            return response.getOutputStream();
        });
    }

    /**
     * Deletes a departed tenant's data (R-8). Dry run by default: with no body, or
     * {@code dryRun: true}, it only reports per-table counts. A real purge needs
     * {@code dryRun: false} and {@code confirmName} equal to the organisation's name, and is
     * refused unless the organisation has been suspended for 30 days and exported since.
     *
     * <p>Gated on {@code ORG.CREATE}, a {@code platform}-module code no tenant administrator can
     * hold, and re-checked in the service.
     */
    @PostMapping("/uid/{uid}/purge")
    @PreAuthorize("@perm.has('ORG.CREATE')")
    public TenantPurgeReportDto purge(@PathVariable String uid,
                                      @RequestBody(required = false) PurgeTenantRequestDto request) {
        return departure.purgeTenant(uid, request);
    }

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public OrganisationDto current() {
        return organisations.current();
    }
}
