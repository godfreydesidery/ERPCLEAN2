package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.entity.SupplierAddress;
import com.erp.modules.parties.domain.entity.SupplierContact;
import com.erp.modules.parties.domain.enums.AddressRole;
import com.erp.modules.parties.repository.SupplierAddressRepository;
import com.erp.modules.parties.repository.SupplierContactRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages contacts and addresses for the supplier party kind (ADR-0040 D-3).
 * Same primary/default invariants as {@link CustomerContactAddressServiceImpl}.
 */
@Service
@Transactional
public class SupplierContactAddressServiceImpl implements SupplierContactAddressService {

    private final SupplierRepository suppliers;
    private final SupplierContactRepository contacts;
    private final SupplierAddressRepository addresses;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public SupplierContactAddressServiceImpl(SupplierRepository suppliers,
                                             SupplierContactRepository contacts,
                                             SupplierAddressRepository addresses,
                                             ScopeGuard scopeGuard,
                                             AuditService audit) {
        this.suppliers = suppliers;
        this.contacts = contacts;
        this.addresses = addresses;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    // =========================================================================
    // CONTACTS
    // =========================================================================

    @Override
    public PartyContactDto addContact(String supplierUid, CreatePartyContactRequest req) {
        Supplier supplier = requireSupplier(supplierUid);
        if (req.isPrimary()) {
            clearPrimaryContact(supplier.getId());
        }
        SupplierContact contact = new SupplierContact(supplier.getCompanyId(), supplier.getId(), actorId());
        contact.setContactPerson(req.contactPerson());
        contact.setPhone(req.phone());
        contact.setEmail(req.email());
        contact.setPrimary(req.isPrimary());

        SupplierContact saved = contacts.save(contact);
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_CONTACT_ADD, "supplier_contacts",
                        saved.getId(), saved.getUid())
                .detail(Map.of("supplierId", supplier.getId(), "isPrimary", req.isPrimary())));
        return PartyContactDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyContactDto> listContacts(String supplierUid) {
        Supplier supplier = requireSupplier(supplierUid);
        return contacts.findBySupplierId(supplier.getId()).stream()
                .map(PartyContactDto::from)
                .toList();
    }

    @Override
    public PartyContactDto updateContact(String supplierUid, String contactUid,
                                         UpdatePartyContactRequest req) {
        Supplier supplier = requireSupplier(supplierUid);
        SupplierContact contact = requireContact(contactUid, supplier.getId());
        if (req.isPrimary() && !contact.isPrimary()) {
            clearPrimaryContact(supplier.getId());
        }
        contact.setContactPerson(req.contactPerson());
        contact.setPhone(req.phone());
        contact.setEmail(req.email());
        contact.setPrimary(req.isPrimary());
        contact.setUpdatedAt(Instant.now());
        contact.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SUPPLIER_CONTACT_UPDATE, "supplier_contacts",
                contact.getId(), contact.getUid()));
        return PartyContactDto.from(contact);
    }

    @Override
    public void deactivateContact(String supplierUid, String contactUid) {
        Supplier supplier = requireSupplier(supplierUid);
        SupplierContact contact = requireContact(contactUid, supplier.getId());
        contact.setStatus(MasterStatus.INACTIVE);
        contact.setPrimary(false);
        contact.setUpdatedAt(Instant.now());
        contact.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_CONTACT_DEACTIVATE, "supplier_contacts",
                contact.getId(), contact.getUid()));
    }

    // =========================================================================
    // ADDRESSES
    // =========================================================================

    @Override
    public PartyAddressDto addAddress(String supplierUid, CreatePartyAddressRequest req) {
        Supplier supplier = requireSupplier(supplierUid);
        if (req.isDefault()) {
            clearDefaultAddress(supplier.getId(), req.addressRole());
        }
        SupplierAddress address = new SupplierAddress(supplier.getCompanyId(), supplier.getId(),
                req.addressRole(), actorId());
        applyAddressFields(address, req.isDefault(), req.country(), req.addressLine1(),
                req.addressLine2(), req.city(), req.region(), req.postalCode());

        SupplierAddress saved = addresses.save(address);
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_ADDRESS_ADD, "supplier_addresses",
                        saved.getId(), saved.getUid())
                .detail(Map.of("supplierId", supplier.getId(), "role", req.addressRole().name())));
        return PartyAddressDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyAddressDto> listAddresses(String supplierUid) {
        Supplier supplier = requireSupplier(supplierUid);
        return addresses.findBySupplierId(supplier.getId()).stream()
                .map(PartyAddressDto::from)
                .toList();
    }

    @Override
    public PartyAddressDto updateAddress(String supplierUid, String addressUid,
                                          UpdatePartyAddressRequest req) {
        Supplier supplier = requireSupplier(supplierUid);
        SupplierAddress address = requireAddress(addressUid, supplier.getId());
        if (req.isDefault() && !address.isDefault()) {
            clearDefaultAddress(supplier.getId(), req.addressRole());
        }
        address.setAddressRole(req.addressRole());
        applyAddressFields(address, req.isDefault(), req.country(), req.addressLine1(),
                req.addressLine2(), req.city(), req.region(), req.postalCode());
        address.setUpdatedAt(Instant.now());
        address.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SUPPLIER_ADDRESS_UPDATE, "supplier_addresses",
                address.getId(), address.getUid()));
        return PartyAddressDto.from(address);
    }

    @Override
    public void deactivateAddress(String supplierUid, String addressUid) {
        Supplier supplier = requireSupplier(supplierUid);
        SupplierAddress address = requireAddress(addressUid, supplier.getId());
        address.setStatus(MasterStatus.INACTIVE);
        address.setDefault(false);
        address.setUpdatedAt(Instant.now());
        address.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.SUPPLIER_ADDRESS_DEACTIVATE, "supplier_addresses",
                address.getId(), address.getUid()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Supplier requireSupplier(String uid) {
        Supplier s = Lookups.orNotFound(suppliers.findByUid(uid), "Supplier", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), s.getCompanyId());
        return s;
    }

    private SupplierContact requireContact(String contactUid, Long supplierId) {
        SupplierContact c = Lookups.orNotFound(contacts.findByUid(contactUid), "SupplierContact", contactUid);
        if (!c.getSupplierId().equals(supplierId)) {
            throw new NotFoundException("Supplier contact not found.");
        }
        return c;
    }

    private SupplierAddress requireAddress(String addressUid, Long supplierId) {
        SupplierAddress a = Lookups.orNotFound(addresses.findByUid(addressUid), "SupplierAddress", addressUid);
        if (!a.getSupplierId().equals(supplierId)) {
            throw new NotFoundException("Supplier address not found.");
        }
        return a;
    }

    private void clearPrimaryContact(Long supplierId) {
        contacts.findBySupplierIdAndIsPrimaryTrue(supplierId).ifPresent(existing -> {
            existing.setPrimary(false);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId());
        });
    }

    private void clearDefaultAddress(Long supplierId, AddressRole role) {
        addresses.findBySupplierIdAndAddressRoleAndIsDefaultTrue(supplierId, role).ifPresent(existing -> {
            existing.setDefault(false);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId());
        });
    }

    private static void applyAddressFields(SupplierAddress a, boolean isDefault, String country,
                                            String line1, String line2, String city,
                                            String region, String postalCode) {
        a.setDefault(isDefault);
        a.setCountry(country);
        a.setAddressLine1(line1);
        a.setAddressLine2(line2);
        a.setCity(city);
        a.setRegion(region);
        a.setPostalCode(postalCode);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
