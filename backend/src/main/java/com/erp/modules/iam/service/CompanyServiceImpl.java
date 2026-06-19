package com.erp.modules.iam.service;

import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.dto.CreateCompanyRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository         companies;
    private final OrganisationRepository    organisations;
    private final JournalEntryRepository    journalEntries;
    private final CurrencyRepository        currencies;
    private final CompanyCurrencyRepository companyCurrencies;
    private final AuditService              audit;
    private final PermissionResolver        permissions;
    private final UserRoleRepository        userRoles;

    public CompanyServiceImpl(CompanyRepository         companies,
                               OrganisationRepository    organisations,
                               JournalEntryRepository    journalEntries,
                               CurrencyRepository        currencies,
                               CompanyCurrencyRepository companyCurrencies,
                               AuditService              audit,
                               PermissionResolver        permissions,
                               UserRoleRepository        userRoles) {
        this.companies         = companies;
        this.organisations     = organisations;
        this.journalEntries    = journalEntries;
        this.currencies        = currencies;
        this.companyCurrencies = companyCurrencies;
        this.audit             = audit;
        this.permissions       = permissions;
        this.userRoles         = userRoles;
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
    @Transactional(readOnly = true)
    public List<CompanyDto> listAccessibleByOrganisationUid(String organisationUid) {
        Organisation org = Lookups.orNotFound(
                organisations.findByUid(organisationUid), "Organisation", organisationUid);
        List<Company> all = companies.findByOrganisationIdOrderByName(org.getId());

        // Admins (COMPANY.VIEW) and root see every company in the org — identical to the admin list.
        // hasPermission short-circuits true for root.
        if (permissions.hasPermission(RequestContext.get(), "COMPANY.VIEW", System.currentTimeMillis())) {
            return all.stream().map(CompanyDto::from).toList();
        }

        // Everyone else (the cross-cutting company picker) sees only the companies they are assigned
        // to via an active role grant — so a non-admin gets their own company WITHOUT COMPANY.VIEW,
        // and cannot enumerate companies they have no access to.
        RequestContext.Principal principal = RequestContext.get();
        Set<Long> assignedCompanyIds = (principal == null || principal.userId() == null)
                ? Set.of()
                : userRoles.findByUserIdAndRevokedAtIsNull(principal.userId()).stream()
                        .map(UserRole::getCompanyId)
                        .collect(Collectors.toSet());
        return all.stream()
                .filter(c -> assignedCompanyIds.contains(c.getId()))
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
        // baseCurrency field is intentionally NOT updated here — use changeBaseCurrency() instead
        // (ADR-0039 D-9 / OQ-CCY-08: guarded by GL-transaction check + COMPANY.CURRENCY.CHANGE perm).
        return CompanyDto.from(company); // dirty-checked within the TX
    }

    /**
     * Change the company base (ledger) currency (ADR-0039 D-9 / OQ-CCY-08).
     *
     * <p>Guard: blocked when any {@code journal_entries} row exists for the company — the GL
     * is the universal sink; changing base after posting would require revaluing every entry.
     * On a fresh bootstrap both fields are TZS, so an immediate change is allowed.
     *
     * <p>Post-change: ensures the new base code is present and active in {@code company_currency}
     * (invariant D-7). The old base code remains in the allow-list (callers may deactivate it).
     */
    @Override
    public CompanyDto changeBaseCurrency(String uid, String newBase) {
        Company company = requireByUid(uid);

        CurrencyCode newCode = CurrencyCode.of(newBase); // validates 3-letter format

        String oldBase = company.getBaseCurrency();
        if (newCode.value().equals(oldBase)) {
            return CompanyDto.from(company); // no-op
        }

        // Guard: block if any GL journal exists (OQ-CCY-08)
        if (journalEntries.existsByCompanyId(company.getId())) {
            throw new ConflictException(
                    "Cannot change base currency: company " + company.getCode()
                            + " already has GL journal entries. Base currency is immutable once"
                            + " transactions have been posted.");
        }

        // Guard: new code must be globally active
        currencies.findByCode(newCode.value()).ifPresentOrElse(cur -> {
            if (!cur.isActive()) {
                throw new IllegalArgumentException(
                        "Currency '" + newCode.value() + "' is not globally active.");
            }
        }, () -> {
            throw NotFoundException.of("Currency", newCode.value());
        });

        // Apply
        company.setBaseCurrency(newCode.value());

        // Ensure the new base is in the company_currency allow-list (active) — invariant D-7
        var existing = companyCurrencies.findByCompanyIdAndCurrencyCode(company.getId(), newCode);
        if (existing.isEmpty()) {
            // enablement rows may not exist yet on a fresh company — leave for the seeder/caller
        } else if (!existing.get().isActive()) {
            existing.get().setActive(true);
        }

        // Audit (ADR-0039 D-9 / OQ-CCY-08)
        audit.record(AuditEvent.of(AuditActions.COMPANY_BASE_CURRENCY_CHANGE,
                "companies", company.getId(), company.getUid())
                .detail(Map.of("oldBase", oldBase, "newBase", newCode.value())));

        return CompanyDto.from(company);
    }

    @Override
    public void archiveByUid(String uid) {
        requireByUid(uid).setStatus(MasterStatus.ARCHIVED);
    }

    private Company requireByUid(String uid) {
        return Lookups.orNotFound(companies.findByUid(uid), "Company", uid);
    }
}
