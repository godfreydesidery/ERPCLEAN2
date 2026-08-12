package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.MoneyDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateCustomerRequest;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.CustomerBranch;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.CustomerSegment;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerBranchRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer master administration (FR-PARTY-01, ADR-0006).
 * Validation split per ADR-0006 D-6: unconditional guards in the DB; conditional rules here.
 */
@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customers;
    private final CustomerBranchRepository customerBranches;
    private final PaymentTermsRepository paymentTermsRepo;
    private final PriceListRepository priceListRepo;
    private final AgentRepository agentRepo;
    private final PartyCodeGenerator codeGen;
    private final PartyBranchGuard branchGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public CustomerServiceImpl(CustomerRepository customers,
                               CustomerBranchRepository customerBranches,
                               PaymentTermsRepository paymentTermsRepo,
                               PriceListRepository priceListRepo,
                               AgentRepository agentRepo,
                               PartyCodeGenerator codeGen,
                               PartyBranchGuard branchGuard,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.customers = customers;
        this.customerBranches = customerBranches;
        this.paymentTermsRepo = paymentTermsRepo;
        this.priceListRepo = priceListRepo;
        this.agentRepo = agentRepo;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public CustomerDto create(CreateCustomerRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered(),
                req.customerKind(), req.displayName());
        validateFkOwnership(req.companyId(), req.paymentTermsId(),
                req.defaultPriceListId(), req.defaultAgentId());

        String code = codeGen.next(req.companyId(), "CUSTOMER");
        Customer c = new Customer(req.companyId(), code, req.partyType(), req.displayName(),
                req.customerKind(), actorId());
        applyCommon(c, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        c.setCustomerKind(req.customerKind());
        c.setCreditLimit(MoneyDto.toMoney(req.creditLimit()));
        c.setPaymentTermsDays(req.paymentTermsDays());
        c.setPaymentTermsId(req.paymentTermsId());
        applyDefaults(c, req.country(), req.defaultPriceListId(), req.defaultAgentId(),
                req.segment(), req.taxExempt(), req.taxExemptionRef(), req.defaultCurrency());

        Customer saved = customers.save(c);
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_CREATE, "customers",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(),
                        "partyType", saved.getPartyType().name(),
                        "customerKind", saved.getCustomerKind().name())));
        return CustomerDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDto getByUid(String uid) {
        // Security fix (finding 2): uid is not authorization — scope-check the loaded entity's company.
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        return CustomerDto.from(c);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1): guard before querying — prevents cross-company list via
        // client-supplied companyId. Root is permitted for any company (audited by assertCanActIn).
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return customers.search(companyId, q, pageable).map(CustomerDto::from);
        }
        return customers.findByCompanyId(companyId, pageable).map(CustomerDto::from);
    }

    @Override
    public CustomerDto updateByUid(String uid, UpdateCustomerRequest req) {
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered(),
                req.customerKind(), req.displayName());
        validateFkOwnership(c.getCompanyId(), req.paymentTermsId(),
                req.defaultPriceListId(), req.defaultAgentId());

        applyCommon(c, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        c.setCustomerKind(req.customerKind());
        c.setCreditLimit(MoneyDto.toMoney(req.creditLimit()));
        c.setPaymentTermsDays(req.paymentTermsDays());
        c.setPaymentTermsId(req.paymentTermsId());
        applyDefaults(c, req.country(), req.defaultPriceListId(), req.defaultAgentId(),
                req.segment(), req.taxExempt(), req.taxExemptionRef(), req.defaultCurrency());
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CUSTOMER_UPDATE, "customers", c.getId(), c.getUid()));
        return CustomerDto.from(c);
    }

    @Override
    public void archiveByUid(String uid) {
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        MasterStatus prev = c.getStatus();
        c.setStatus(MasterStatus.ARCHIVED);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_ARCHIVE, "customers", c.getId(), c.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        MasterStatus prev = c.getStatus();
        c.setStatus(MasterStatus.ACTIVE);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_RESTORE, "customers", c.getId(), c.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest req) {
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(c.getCompanyId(), req.branchUid());

        if (customerBranches.findByCustomerIdAndBranchId(c.getId(), branchId).isPresent()) {
            throw new ConflictException("Customer is already associated with that branch.");
        }
        CustomerBranch assoc = customerBranches.save(new CustomerBranch(c, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_BRANCH_ADD, "customers", c.getId(), c.getUid())
                .detail(Map.of("branchUid", req.branchUid())));
        return PartyBranchDto.of(assoc.getBranchId(), assoc.getAssignedAt(), assoc.getAssignedBy());
    }

    @Override
    public void removeBranch(String uid, String branchUid) {
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(c.getCompanyId(), branchUid);
        customerBranches.deleteByCustomerIdAndBranchId(c.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_BRANCH_REMOVE, "customers",
                        c.getId(), c.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyBranchDto> listBranches(String uid) {
        // Security fix (finding 3): scope-check the party's company before listing its branches.
        Customer c = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        return customerBranches.findByCustomerId(c.getId()).stream()
                .map(a -> PartyBranchDto.of(a.getBranchId(), a.getAssignedAt(), a.getAssignedBy()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Customer require(String uid) {
        return Lookups.orNotFound(customers.findByUid(uid), "Customer", uid);
    }

    /**
     * Verifies that each supplied optional FK belongs to {@code companyId}.
     * Throws {@link NotFoundException} (404) on mismatch — same as a non-existent resource,
     * avoiding existence leaks across company boundaries (CONFUSED_DEPUTY fix).
     */
    private void validateFkOwnership(Long companyId, Long paymentTermsId,
                                     Long defaultPriceListId, Long defaultAgentId) {
        if (paymentTermsId != null
                && !paymentTermsRepo.existsByCompanyIdAndId(companyId, paymentTermsId)) {
            throw new NotFoundException("PaymentTerms not found.");
        }
        if (defaultPriceListId != null
                && !priceListRepo.existsByCompanyIdAndId(companyId, defaultPriceListId)) {
            throw new NotFoundException("PriceList not found.");
        }
        if (defaultAgentId != null
                && !agentRepo.existsByCompanyIdAndId(companyId, defaultAgentId)) {
            throw new NotFoundException("Agent not found.");
        }
    }

    /**
     * Conditional identifier validation per ADR-0006 D-6.
     * DB handles the unconditional checks (NOT NULL display_name, VRN-needs-VAT CHECK, kind CHECK).
     * The service handles the conditional ones.
     */
    private void validateIdentifiers(PartyType partyType, String tin, String vrn,
                                     Boolean vatRegistered, CustomerKind kind, String displayName) {
        // BR-PARTY-04: business must have TIN
        if (partyType == PartyType.BUSINESS && (tin == null || tin.isBlank())) {
            throw new IllegalArgumentException("A business customer must have a Tax Identification Number (TIN).");
        }
        // BR-PARTY-06: VRN only if vat_registered
        boolean isVatRegistered = Boolean.TRUE.equals(vatRegistered);
        if (vrn != null && !vrn.isBlank() && !isVatRegistered) {
            throw new IllegalArgumentException("A VAT Registration Number (VRN) can only be entered when the customer is marked as VAT-registered.");
        }
        // BR-PARTY-07: credit customer minimum identity (business + TIN already checked above)
        if (kind == CustomerKind.CREDIT_ACCOUNT && (displayName == null || displayName.isBlank())) {
            throw new IllegalArgumentException("A credit account customer must have a display name.");
        }
    }

    private static void applyCommon(Customer c, PartyType partyType, String displayName,
                                    String legalName, String tin, Boolean vatRegistered, String vrn,
                                    String businessRegNo, String mobileMoneyNo, String phone,
                                    String email, String physicalAddress, String postalAddress,
                                    String region, String district) {
        c.setPartyType(partyType);
        c.setDisplayName(displayName);
        c.setLegalName(legalName);
        c.setTin(tin);
        c.setVatRegistered(Boolean.TRUE.equals(vatRegistered));
        c.setVrn(vrn);
        c.setBusinessRegNo(businessRegNo);
        c.setMobileMoneyNo(mobileMoneyNo);
        c.setPhone(phone);
        c.setEmail(email);
        c.setPhysicalAddress(physicalAddress);
        c.setPostalAddress(postalAddress);
        c.setRegion(region);
        c.setDistrict(district);
    }

    /**
     * Applies the optional P2 D5 / P2-mechanical master-data defaults. Each field is only set when
     * the request supplied a value (null = keep the entity default), mirroring the partial-update
     * shape used elsewhere. {@code defaultCurrency} is parsed via {@link CurrencyCode#ofNullable}.
     */
    private static void applyDefaults(Customer c, String country, Long defaultPriceListId,
                                      Long defaultAgentId, CustomerSegment segment, Boolean taxExempt,
                                      String taxExemptionRef, String defaultCurrency) {
        // Same trap the supplier path had: country is VARCHAR(2), so a typed-out country name used
        // to fail on the DB constraint with a message that named no field at all.
        c.setCountry(PartyFieldValidator.isoCountryCode(country));
        c.setDefaultPriceListId(defaultPriceListId);
        c.setDefaultAgentId(defaultAgentId);
        if (segment != null) {
            c.setSegment(segment);
        }
        if (taxExempt != null) {
            c.setTaxExempt(taxExempt);
        }
        c.setTaxExemptionRef(taxExemptionRef);
        c.setDefaultCurrency(CurrencyCode.ofNullable(defaultCurrency));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
