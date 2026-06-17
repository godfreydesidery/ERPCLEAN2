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

    /**
     * Change the base (ledger) currency of a company (ADR-0039 D-9 / OQ-CCY-08).
     *
     * <p>Only allowed when no {@code journal_entries} row exists for the company.
     * Requires {@code COMPANY.CURRENCY.CHANGE} permission at the call site.
     * Emits an audit log entry on success.
     *
     * @param uid         company uid
     * @param newBase     new ISO-4217 base currency code
     * @return updated company DTO
     */
    CompanyDto changeBaseCurrency(String uid, String newBase);

    void archiveByUid(String uid);
}
