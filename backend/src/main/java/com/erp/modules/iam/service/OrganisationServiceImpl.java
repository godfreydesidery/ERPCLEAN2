package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.modules.iam.domain.dto.CreateTenantRequest;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrganisationServiceImpl implements OrganisationService {

    private final OrganisationRepository organisations;
    private final com.erp.platform.bootstrap.TenantProvisioningService provisioning;
    private final com.erp.platform.audit.AuditService audit;

    public OrganisationServiceImpl(OrganisationRepository organisations,
                                   com.erp.platform.bootstrap.TenantProvisioningService provisioning,
                                   com.erp.platform.audit.AuditService audit) {
        this.organisations = organisations;
        this.provisioning = provisioning;
        this.audit = audit;
    }

    @Override
    public OrganisationDto current() {
        // P3-7 (G5): the CALLER'S organisation. findFirstByOrderByIdAsc returned organisation #1 to
        // everybody, and 146 Angular components bootstrap their company picker from this call - so
        // on a shared instance every tenant but the lowest-id one would get somebody else's.
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && principal.organisationId() != null) {
            return organisations.findScopedById(principal.organisationId())
                    .map(OrganisationDto::from)
                    .orElseThrow(() -> new NotFoundException("No organisation exists yet."));
        }
        return organisations.findFirstByOrderByIdAsc()
                .map(OrganisationDto::from)
                .orElseThrow(() -> new NotFoundException(
                        "No organisation exists yet. Bootstrap the deployment first."));
    }

    @Override
    @Transactional
    public OrganisationDto createTenant(CreateTenantRequest request) {
        // P5-2 (ADR-0062). Delegates to the SAME service bootstrap uses, so a tenant created here is
        // identical to the one a fresh install produces - no second seeding path to drift.
        var provisioned = provisioning.provision(
                new com.erp.platform.bootstrap.TenantProvisioningService.NewTenantRequest(
                        request.organisationName(),
                        blankToNull(request.timeZone(), "Africa/Dar_es_Salaam"),
                        request.companyCode(), request.companyName(),
                        request.branchCode(), request.branchName(),
                        request.adminUsername(), request.adminPassword(),
                        blankToNull(request.adminDisplayName(), request.adminUsername()),
                        blankToNull(request.baseCurrency(), "TZS"),
                        blankToNull(request.defaultCurrency(), "TZS"),
                        request.enabledCurrencies() == null || request.enabledCurrencies().isEmpty()
                                ? List.of("TZS") : request.enabledCurrencies()));

        // High-severity by nature: this is the vendor creating a customer. The admin password is
        // never part of the event - only who was created, and under what name.
        audit.record(AuditEvent.of(AuditActions.ORG_CREATE, "organisations",
                        provisioned.organisationId(), null)
                .detail(java.util.Map.of(
                        "organisationName", provisioned.organisationName(),
                        "companyCode", provisioned.companyCode(),
                        "adminUsername", provisioned.adminUsername())));

        return organisations.findScopedById(provisioned.organisationId())
                .map(OrganisationDto::from)
                .orElseThrow(() -> new NotFoundException("Organisation was not created."));
    }

    @Override
    @Transactional
    public OrganisationDto setStatus(String uid, boolean active) {
        // P5-3. Suspension is enforced at LOGIN (AuthServiceImpl.assertTenantIsOpen), not per
        // request: an already-issued access token stays valid for its remaining minutes. That is
        // deliberate - suspension is a commercial action, not an incident response, and tearing a
        // cashier's session out mid-sale would be the wrong tool. Use user deactivation for
        // incidents.
        var org = organisations.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Organisation not found."));
        org.setStatus(active ? MasterStatus.ACTIVE : MasterStatus.INACTIVE);
        organisations.save(org);

        audit.record(AuditEvent.of(active ? AuditActions.ORG_RESUME : AuditActions.ORG_SUSPEND,
                        "organisations", org.getId(), org.getUid()));

        return OrganisationDto.from(org);
    }

    private static String blankToNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public List<OrganisationDto> list() {
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && principal.organisationId() != null) {
            return organisations.findAllVisibleTo(principal.organisationId()).stream()
                    .map(OrganisationDto::from)
                    .toList();
        }
        return organisations.findAll(Sort.by("name")).stream()
                .map(OrganisationDto::from)
                .toList();
    }
}
