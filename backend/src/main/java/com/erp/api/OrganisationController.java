package com.erp.api;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.service.OrganisationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation read endpoints (ARCHITECTURE §7). The organisation is created by bootstrap, so this
 * is read-only. {@code /current} lets the web resolve the deployment's single organisation (by name)
 * instead of requiring an admin to paste a uid. Returns raw DTOs — {@code ApiResponseAdvice} wraps
 * them. Permission gate (COMPANY.VIEW / a future ORG.VIEW) wired in Slice 3 when RBAC turns on.
 */
@RestController
@RequestMapping("/api/v1/organisations")
public class OrganisationController {

    private final OrganisationService organisations;

    public OrganisationController(OrganisationService organisations) {
        this.organisations = organisations;
    }

    @GetMapping
    public List<OrganisationDto> list() {
        return organisations.list();
    }

    @GetMapping("/current")
    public OrganisationDto current() {
        return organisations.current();
    }
}
