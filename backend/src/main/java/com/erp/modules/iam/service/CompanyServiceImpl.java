package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository companies;
    private final OrganisationRepository organisations;

    public CompanyServiceImpl(CompanyRepository companies, OrganisationRepository organisations) {
        this.companies = companies;
        this.organisations = organisations;
    }

    @Override
    public CompanyDto create(CreateCompanyRequest request) {
        Organisation org = Lookups.orNotFound(
                organisations.findByUid(request.organisationUid()), "Organisation",
                request.organisationUid());

        if (companies.existsByOrganisationIdAndCode(org.getId(), request.code())) {
            throw new ConflictException("Company code already exists: " + request.code());
        }

        Company company = new Company(org, request.code(), request.name());
        company.setLegalName(request.legalName());
        company.setTaxId(request.taxId());
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            company.setTimeZone(request.timeZone());
        } else {
            company.setTimeZone(org.getDefaultTimeZone());
        }
        return CompanyDto.from(companies.save(company));
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyDto getByUid(String uid) {
        return CompanyDto.from(requireByUid(uid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompanyDto> listByOrganisationUid(String organisationUid) {
        Organisation org = Lookups.orNotFound(
                organisations.findByUid(organisationUid), "Organisation", organisationUid);
        return companies.findByOrganisationIdOrderByName(org.getId()).stream()
                .map(CompanyDto::from)
                .toList();
    }

    @Override
    public CompanyDto updateByUid(String uid, UpdateCompanyRequest request) {
        Company company = requireByUid(uid);
        company.setName(request.name());
        company.setLegalName(request.legalName());
        company.setTaxId(request.taxId());
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            company.setTimeZone(request.timeZone());
        }
        return CompanyDto.from(company); // dirty-checked within the TX
    }

    @Override
    public void archiveByUid(String uid) {
        requireByUid(uid).setStatus(MasterStatus.ARCHIVED);
    }

    private Company requireByUid(String uid) {
        return Lookups.orNotFound(companies.findByUid(uid), "Company", uid);
    }
}
