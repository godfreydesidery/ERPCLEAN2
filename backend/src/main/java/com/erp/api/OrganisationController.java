package com.erp.api;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.service.OrganisationService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
    @PreAuthorize("@perm.has('COMPANY.VIEW')")
    public List<OrganisationDto> list() {
        return organisations.list();
    }

    // /current is the web shell's bootstrap call (which org am I in?) — any authenticated user may
    // read it; it carries no sensitive data and is needed before any permission-scoped screen loads.
    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public OrganisationDto current() {
        return organisations.current();
    }
}
