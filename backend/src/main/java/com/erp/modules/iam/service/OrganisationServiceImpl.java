package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrganisationServiceImpl implements OrganisationService {

    private final OrganisationRepository organisations;

    public OrganisationServiceImpl(OrganisationRepository organisations) {
        this.organisations = organisations;
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
