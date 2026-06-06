package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.OrganisationDto;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
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
        return organisations.findFirstByOrderByIdAsc()
                .map(OrganisationDto::from)
                .orElseThrow(() -> new NotFoundException(
                        "No organisation exists yet. Bootstrap the deployment first."));
    }

    @Override
    public List<OrganisationDto> list() {
        return organisations.findAll(Sort.by("name")).stream()
                .map(OrganisationDto::from)
                .toList();
    }
}
