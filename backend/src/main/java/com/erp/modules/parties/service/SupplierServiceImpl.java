package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierBankAccountRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.SupplierBankAccountDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierBankAccountRequest;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.entity.SupplierBankAccount;
import com.erp.modules.parties.domain.entity.SupplierBranch;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.SupplierBankAccountRepository;
import com.erp.modules.parties.repository.SupplierBranchRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
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

/** Supplier master administration (FR-PARTY-02, ADR-0006). */
@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository suppliers;
    private final SupplierBranchRepository supplierBranches;
    private final SupplierBankAccountRepository bankAccounts;
    private final PartyCodeGenerator codeGen;
    private final PartyBranchGuard branchGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public SupplierServiceImpl(SupplierRepository suppliers,
                               SupplierBranchRepository supplierBranches,
                               SupplierBankAccountRepository bankAccounts,
                               PartyCodeGenerator codeGen,
                               PartyBranchGuard branchGuard,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.suppliers = suppliers;
        this.supplierBranches = supplierBranches;
        this.bankAccounts = bankAccounts;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public SupplierDto create(CreateSupplierRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());

        String code = codeGen.next(req.companyId(), "SUPPLIER");
        Supplier s = new Supplier(req.companyId(), code, req.partyType(), req.displayName(),
                req.supplierKind(), actorId());
        applyCommon(s, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        s.setSupplierKind(req.supplierKind());
        s.setPaymentTermsDays(req.paymentTermsDays());
        s.setPaymentTermsId(req.paymentTermsId());
        applyDefaults(s, req.country(), req.defaultCurrency(), req.leadTimeDays(),
                req.minOrderValue(), req.defaultWhtTypeId());

        Supplier saved = suppliers.save(s);
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_CREATE, "suppliers",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(),
                        "partyType", saved.getPartyType().name(),
                        "supplierKind", saved.getSupplierKind().name())));
        return SupplierDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierDto getByUid(String uid) {
        // Security fix (finding 2): uid is not authorization — scope-check the loaded entity's company.
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        return SupplierDto.from(s);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1): guard before querying — prevents cross-company list via
        // client-supplied companyId. Root is permitted for any company (audited by assertCanActIn).
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return suppliers.search(companyId, q, pageable).map(SupplierDto::from);
        }
        return suppliers.findByCompanyId(companyId, pageable).map(SupplierDto::from);
    }

    @Override
    public SupplierDto updateByUid(String uid, UpdateSupplierRequest req) {
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());

        applyCommon(s, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        s.setSupplierKind(req.supplierKind());
        s.setPaymentTermsDays(req.paymentTermsDays());
        s.setPaymentTermsId(req.paymentTermsId());
        applyDefaults(s, req.country(), req.defaultCurrency(), req.leadTimeDays(),
                req.minOrderValue(), req.defaultWhtTypeId());
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SUPPLIER_UPDATE, "suppliers", s.getId(), s.getUid()));
        return SupplierDto.from(s);
    }

    @Override
    public void archiveByUid(String uid) {
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        MasterStatus prev = s.getStatus();
        s.setStatus(MasterStatus.ARCHIVED);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_ARCHIVE, "suppliers", s.getId(), s.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        MasterStatus prev = s.getStatus();
        s.setStatus(MasterStatus.ACTIVE);
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_RESTORE, "suppliers", s.getId(), s.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest req) {
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(s.getCompanyId(), req.branchUid());

        if (supplierBranches.findBySupplierIdAndBranchId(s.getId(), branchId).isPresent()) {
            throw new ConflictException("Supplier is already associated with that branch.");
        }
        SupplierBranch assoc = supplierBranches.save(new SupplierBranch(s, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BRANCH_ADD, "suppliers", s.getId(), s.getUid())
                .detail(Map.of("branchUid", req.branchUid())));
        return PartyBranchDto.of(assoc.getBranchId(), assoc.getAssignedAt(), assoc.getAssignedBy());
    }

    @Override
    public void removeBranch(String uid, String branchUid) {
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(s.getCompanyId(), branchUid);
        supplierBranches.deleteBySupplierIdAndBranchId(s.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BRANCH_REMOVE, "suppliers",
                        s.getId(), s.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyBranchDto> listBranches(String uid) {
        // Security fix (finding 3): scope-check the party's company before listing its branches.
        Supplier s = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        return supplierBranches.findBySupplierId(s.getId()).stream()
                .map(a -> PartyBranchDto.of(a.getBranchId(), a.getAssignedAt(), a.getAssignedBy()))
                .toList();
    }

    // ---- bank accounts (D-4) ------------------------------------------------

    @Override
    public SupplierBankAccountDto addBankAccount(String supplierUid,
                                                  CreateSupplierBankAccountRequest req) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        validateBankAccountIdentifiers(req.accountNo(), req.iban());

        if (req.isDefault()) {
            // clear existing default before setting the new one
            bankAccounts.findBySupplierIdAndIsDefaultTrue(s.getId())
                    .ifPresent(existing -> { existing.setDefault(false); bankAccounts.save(existing); });
        }

        SupplierBankAccount sba = new SupplierBankAccount(
                s.getCompanyId(), s.getId(),
                req.bankName(), req.accountName(), req.accountNo(), req.iban(), actorId());
        sba.setBankBranch(req.bankBranch());
        sba.setSwiftBic(req.swiftBic());
        sba.setCurrency(req.currency() != null ? CurrencyCode.of(req.currency()) : null);
        sba.setDefault(req.isDefault());

        SupplierBankAccount saved = bankAccounts.save(sba);
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BANK_ACCOUNT_CREATE, "supplier_bank_accounts",
                saved.getId(), saved.getUid())
                .detail(Map.of("supplierUid", supplierUid, "bankName", saved.getBankName())));
        return SupplierBankAccountDto.from(saved);
    }

    @Override
    public SupplierBankAccountDto updateBankAccount(String supplierUid, String bankAccountUid,
                                                     UpdateSupplierBankAccountRequest req) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        SupplierBankAccount sba = requireBankAccount(bankAccountUid, s.getId());
        validateBankAccountIdentifiers(req.accountNo(), req.iban());

        sba.setBankName(req.bankName());
        sba.setAccountName(req.accountName());
        sba.setAccountNo(req.accountNo());
        sba.setBankBranch(req.bankBranch());
        sba.setIban(req.iban());
        sba.setSwiftBic(req.swiftBic());
        sba.setCurrency(req.currency() != null ? CurrencyCode.of(req.currency()) : null);
        sba.setUpdatedAt(Instant.now());
        sba.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BANK_ACCOUNT_UPDATE, "supplier_bank_accounts",
                sba.getId(), sba.getUid()));
        return SupplierBankAccountDto.from(sba);
    }

    @Override
    public void archiveBankAccount(String supplierUid, String bankAccountUid) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        SupplierBankAccount sba = requireBankAccount(bankAccountUid, s.getId());
        sba.setStatus(com.erp.platform.common.domain.MasterStatus.ARCHIVED);
        sba.setUpdatedAt(Instant.now());
        sba.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BANK_ACCOUNT_ARCHIVE,
                "supplier_bank_accounts", sba.getId(), sba.getUid()));
    }

    @Override
    public void restoreBankAccount(String supplierUid, String bankAccountUid) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        SupplierBankAccount sba = requireBankAccount(bankAccountUid, s.getId());
        sba.setStatus(com.erp.platform.common.domain.MasterStatus.ACTIVE);
        sba.setUpdatedAt(Instant.now());
        sba.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BANK_ACCOUNT_RESTORE,
                "supplier_bank_accounts", sba.getId(), sba.getUid()));
    }

    @Override
    public void setDefaultBankAccount(String supplierUid, String bankAccountUid) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        SupplierBankAccount sba = requireBankAccount(bankAccountUid, s.getId());
        // clear existing default
        bankAccounts.findBySupplierIdAndIsDefaultTrue(s.getId())
                .ifPresent(existing -> { existing.setDefault(false); bankAccounts.save(existing); });
        sba.setDefault(true);
        sba.setUpdatedAt(Instant.now());
        sba.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_BANK_ACCOUNT_SET_DEFAULT,
                "supplier_bank_accounts", sba.getId(), sba.getUid()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierBankAccountDto> listBankAccounts(String supplierUid) {
        Supplier s = require(supplierUid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        return bankAccounts.findBySupplierId(s.getId()).stream()
                .map(SupplierBankAccountDto::from)
                .toList();
    }

    private SupplierBankAccount requireBankAccount(String uid, Long supplierId) {
        SupplierBankAccount sba = Lookups.orNotFound(
                bankAccounts.findByUid(uid), "SupplierBankAccount", uid);
        if (!sba.getSupplierId().equals(supplierId)) {
            throw new com.erp.platform.common.api.NotFoundException(
                    "SupplierBankAccount " + uid + " does not belong to this supplier.");
        }
        return sba;
    }

    private static void validateBankAccountIdentifiers(String accountNo, String iban) {
        if ((accountNo == null || accountNo.isBlank()) && (iban == null || iban.isBlank())) {
            throw new IllegalArgumentException(
                    "At least one of accountNo or iban must be provided (D-4 account_no OR iban NOT NULL).");
        }
    }

    private Supplier require(String uid) {
        return Lookups.orNotFound(suppliers.findByUid(uid), "Supplier", uid);
    }

    private void validateIdentifiers(PartyType partyType, String tin, String vrn,
                                     Boolean vatRegistered) {
        if (partyType == PartyType.BUSINESS && (tin == null || tin.isBlank())) {
            throw new IllegalArgumentException("A business supplier must have a TIN (BR-PARTY-04).");
        }
        if (vrn != null && !vrn.isBlank() && !Boolean.TRUE.equals(vatRegistered)) {
            throw new IllegalArgumentException("VRN may only be set when the supplier is VAT-registered (BR-PARTY-06).");
        }
    }

    private static void applyCommon(Supplier s, PartyType partyType, String displayName,
                                    String legalName, String tin, Boolean vatRegistered, String vrn,
                                    String businessRegNo, String mobileMoneyNo, String phone,
                                    String email, String physicalAddress, String postalAddress,
                                    String region, String district) {
        s.setPartyType(partyType);
        s.setDisplayName(displayName);
        s.setLegalName(legalName);
        s.setTin(tin);
        s.setVatRegistered(Boolean.TRUE.equals(vatRegistered));
        s.setVrn(vrn);
        s.setBusinessRegNo(businessRegNo);
        s.setMobileMoneyNo(mobileMoneyNo);
        s.setPhone(phone);
        s.setEmail(email);
        s.setPhysicalAddress(physicalAddress);
        s.setPostalAddress(postalAddress);
        s.setRegion(region);
        s.setDistrict(district);
    }

    /**
     * Applies the optional P2 D5 master-data defaults. {@code defaultCurrency} is parsed via
     * {@link CurrencyCode#ofNullable}; the remaining fields are set as-is (null = clear).
     */
    private static void applyDefaults(Supplier s, String country, String defaultCurrency,
                                      Integer leadTimeDays, java.math.BigDecimal minOrderValue,
                                      Long defaultWhtTypeId) {
        s.setCountry(country);
        s.setDefaultCurrency(CurrencyCode.ofNullable(defaultCurrency));
        s.setLeadTimeDays(leadTimeDays);
        s.setMinOrderValue(minOrderValue);
        s.setDefaultWhtTypeId(defaultWhtTypeId);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
