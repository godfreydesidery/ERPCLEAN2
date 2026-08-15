package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.api.ConflictException;
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
        // P5-2 (ADR-0062). Delegates to the SAME service bootstrap uses, so there is one seeding path
        // and no second copy to drift. The two tenants are no longer IDENTICAL, and deliberately so:
        // an operator is present here, so this path can carry a tax identity, a price list, a
        // counter customer and a till, while bootstrap - which runs unattended on an empty database,
        // with nobody to ask - passes null for every one of them and creates none.
        var provisioned = provisioning.provision(
                new com.erp.platform.bootstrap.TenantProvisioningService.NewTenantRequest(
                        request.organisationName(),
                        blankToNull(request.timeZone(), "Africa/Dar_es_Salaam"),
                        request.companyCode(), request.companyName(),
                        // Tax identity: NO fallback. Blank means the operator did not know it, and
                        // the answer is to record nothing rather than a value invented here.
                        blankToNull(request.companyLegalName()),
                        blankToNull(request.companyTaxId()),
                        blankToNull(request.companyVrn()),
                        request.branchCode(), request.branchName(),
                        request.adminUsername(), request.adminPassword(),
                        blankToNull(request.adminDisplayName(), request.adminUsername()),
                        blankToNull(request.baseCurrency(), "TZS"),
                        blankToNull(request.defaultCurrency(), "TZS"),
                        request.enabledCurrencies() == null || request.enabledCurrencies().isEmpty()
                                ? List.of("TZS") : request.enabledCurrencies(),
                        // Price list, walk-in customer, till: also no fallback. Each blank one
                        // creates nothing, which is the whole point — a price list named "Standard"
                        // that nobody asked for is worse than none at all.
                        blankToNull(request.priceListCode()),
                        blankToNull(request.priceListName()),
                        // Not blankToNull'd and not defaulted: null here means "the operator did not
                        // state a VAT stance", which the provisioner answers by leaving the entity
                        // default alone rather than picking one.
                        request.priceListIncludesVat(),
                        blankToNull(request.walkInCustomerName()),
                        blankToNull(request.posTillName()),
                        true));   // API-provisioned tenant: the admin's name composes (D-7)

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

        // A CALLER MAY NOT SUSPEND THEIR OWN TENANT. Suspension is enforced at login, so suspending
        // the organisation you are signed in to locks you out - and `resume` requires being signed
        // in. On a single-organisation install that is a hard brick recoverable only by direct SQL
        // against the customer's database. The codebase already states this invariant elsewhere:
        // UserServiceImpl refuses to disable a root admin precisely so somebody can still get back
        // in. Root is NOT exempt here: root is exactly the account that can reach this endpoint.
        RequestContext.Principal caller = RequestContext.get();
        if (!active && caller != null && caller.organisationId() != null
                && caller.organisationId().equals(org.getId())) {
            throw new ConflictException(
                    "You cannot suspend the organisation you are signed in to.");
        }

        org.setStatus(active ? MasterStatus.ACTIVE : MasterStatus.INACTIVE);
        organisations.save(org);

        audit.record(AuditEvent.of(active ? AuditActions.ORG_RESUME : AuditActions.ORG_SUSPEND,
                        "organisations", org.getId(), org.getUid()));

        return OrganisationDto.from(org);
    }

    private static String blankToNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * The no-fallback half of the pair above: an absent key, {@code ""} and {@code "   "} all become
     * null, and null means "create nothing".
     *
     * <p>It exists as its own method rather than as {@code blankToNull(value, null)} because the
     * two-argument helper is really blank-to-FALLBACK — it is what turns a blank time zone into
     * Africa/Dar_es_Salaam — so passing a literal null there reads at the call site like an
     * oversight rather than the decision it is.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
