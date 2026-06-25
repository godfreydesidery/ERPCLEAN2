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
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.repository.Lookups;
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

    /**
     * Enabled-currency allow-list for a freshly provisioned company: Classic EAC-6 + USD + EUR,
     * matching the bootstrap default (ADR-0039 D-9). Shared by {@link #create} and
     * {@link #reprovisionDefaults} so the two paths cannot drift.
     */
    private static final List<String> DEFAULT_ENABLED_CURRENCIES =
            List.of("TZS", "KES", "UGX", "RWF", "BIF", "SSP", "USD", "EUR");

    private final CompanyRepository          companies;
    private final OrganisationRepository     organisations;
    private final JournalEntryRepository     journalEntries;
    private final CurrencyRepository         currencies;
    private final CompanyCurrencyRepository  companyCurrencies;
    private final AuditService               audit;
    private final UserRoleRepository         userRoles;
    private final CompanyProvisioningService provisioner;

    public CompanyServiceImpl(CompanyRepository          companies,
                               OrganisationRepository     organisations,
                               JournalEntryRepository     journalEntries,
                               CurrencyRepository         currencies,
                               CompanyCurrencyRepository  companyCurrencies,
                               AuditService               audit,
                               UserRoleRepository         userRoles,
                               CompanyProvisioningService provisioner) {
        this.companies        = companies;
        this.organisations    = organisations;
        this.journalEntries   = journalEntries;
        this.currencies       = currencies;
        this.companyCurrencies = companyCurrencies;
        this.audit            = audit;
        this.userRoles        = userRoles;
        this.provisioner      = provisioner;
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
        Company saved = companies.save(company);

        // Provision all company-scoped defaults in the same TX so a UI-created company is
        // immediately operational (mirrors BootstrapRunner; mirrors BranchServiceImpl for branch
        // defaults). Base and default currency are both the company's baseCurrency (TZS by default
        // since CreateCompanyRequest carries no currency field); enabled set is the Classic EAC-6 +
        // USD + EUR, same as the bootstrap default (ADR-0039 D-9 / currencyOnCreateApproach).
        String base = saved.getBaseCurrency(); // "TZS" from entity initializer
        provisioner.provisionDefaults(
                saved.getId(),
                base,
                base,   // defaultCurrency == base for a fresh company
                DEFAULT_ENABLED_CURRENCIES);

        return CompanyDto.from(saved);
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

        // Only ROOT sees every company in the org. A non-root user — even one holding COMPANY.VIEW
        // via an all-permissions role scoped to a single company — must see ONLY companies they are
        // assigned to; COMPANY.VIEW is granted per-company and must never leak the whole org's
        // company list (tenant-isolation fix, security audit 2026-06-25).
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && principal.root()) {
            return all.stream().map(CompanyDto::from).toList();
        }

        // Everyone else: only companies they hold an active role grant in.
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

    /**
     * Re-provision all company-scoped defaults for an existing company (idempotent).
     *
     * <p>Intended to heal companies created before provisioning was wired into {@link #create}
     * (e.g. the bootstrap company on a pre-fix deployment, or a company created via an earlier
     * API version). Every seeder guards against duplicates, so re-running on an already-provisioned
     * company is a safe no-op.
     *
     * @param uid company uid
     * @return the company DTO (unchanged — this is a provisioning side-effect only)
     */
    @Override
    public CompanyDto reprovisionDefaults(String uid) {
        Company company = requireByUid(uid);
        String base = company.getBaseCurrency();
        provisioner.provisionDefaults(
                company.getId(),
                base,
                base,
                DEFAULT_ENABLED_CURRENCIES);

        audit.record(AuditEvent.of(AuditActions.COMPANY_PROVISION_DEFAULTS,
                "companies", company.getId(), company.getUid()));

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
