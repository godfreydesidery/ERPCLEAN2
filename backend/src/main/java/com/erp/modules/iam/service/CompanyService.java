package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
import java.util.List;

/**
 * Company administration (DATA-MODEL §1.2). One responsibility: the company aggregate. Controllers
 * depend on this interface, not the impl (DIP).
 */
public interface CompanyService {

    CompanyDto create(CreateCompanyRequest request);

    CompanyDto getByUid(String uid);

    List<CompanyDto> listByOrganisationUid(String organisationUid);

    CompanyDto updateByUid(String uid, UpdateCompanyRequest request);

    void archiveByUid(String uid);
}
