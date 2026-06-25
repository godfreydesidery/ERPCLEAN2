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

    /**
     * Companies the current user may act in (the company-picker source). Admins/root see every
     * company in the org; everyone else sees only the companies they are assigned to via an active
     * role grant. Auth-only — does not require {@code COMPANY.VIEW}.
     */
    List<CompanyDto> listAccessibleByOrganisationUid(String organisationUid);

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

    /**
     * Idempotent re-provision of all company-scoped defaults (UoM, tax rates, GL, AR/AP, cash/bank,
     * inventory, documents, fixed assets, costing dimensions, CRM stages, HR, notifications,
     * manufacturing, currency enablement). Safe to call on an already-provisioned company — every
     * seeder guards against duplicates.
     *
     * <p>Use this to heal companies that were created before provisioning was wired into
     * {@link #create} (e.g. the bootstrap company on a pre-fix deployment).
     *
     * @param uid company uid
     * @return the company DTO (data unchanged — provisioning is a side-effect)
     */
    CompanyDto reprovisionDefaults(String uid);

    void archiveByUid(String uid);
}
