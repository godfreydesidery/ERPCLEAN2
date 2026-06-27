package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.CustomerAddress;
import com.erp.modules.parties.domain.entity.CustomerContact;
import com.erp.modules.parties.domain.enums.AddressRole;
import com.erp.modules.parties.repository.CustomerAddressRepository;
import com.erp.modules.parties.repository.CustomerContactRepository;
import com.erp.modules.parties.repository.CustomerRepository;
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
 * Manages contacts and addresses for the customer party kind (ADR-0040 D-3).
 *
 * <p>Primary-contact rule: at most one ACTIVE contact per customer is primary.
 * When a new contact is created/updated with {@code isPrimary=true}, any existing primary
 * contact for that customer is cleared first (within the same transaction).
 *
 * <p>Default-address rule: at most one address per (customer, addressRole) is default.
 * When a new address is created/updated with {@code isDefault=true}, any existing default
 * for that (customer, role) pair is cleared first (within the same transaction).
 */
@Service
@Transactional
public class CustomerContactAddressServiceImpl implements CustomerContactAddressService {

    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final CustomerAddressRepository addresses;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public CustomerContactAddressServiceImpl(CustomerRepository customers,
                                             CustomerContactRepository contacts,
                                             CustomerAddressRepository addresses,
                                             ScopeGuard scopeGuard,
                                             AuditService audit) {
        this.customers = customers;
        this.contacts = contacts;
        this.addresses = addresses;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    // =========================================================================
    // CONTACTS
    // =========================================================================

    @Override
    public PartyContactDto addContact(String customerUid, CreatePartyContactRequest req) {
        Customer customer = requireCustomer(customerUid);
        if (req.isPrimary()) {
            clearPrimaryContact(customer.getId());
        }
        CustomerContact contact = new CustomerContact(customer.getCompanyId(), customer.getId(), actorId());
        contact.setContactPerson(req.contactPerson());
        contact.setPhone(req.phone());
        contact.setEmail(req.email());
        contact.setPrimary(req.isPrimary());

        CustomerContact saved = contacts.save(contact);
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_CONTACT_ADD, "customer_contacts",
                        saved.getId(), saved.getUid())
                .detail(Map.of("customerId", customer.getId(), "isPrimary", req.isPrimary())));
        return PartyContactDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyContactDto> listContacts(String customerUid) {
        Customer customer = requireCustomer(customerUid);
        return contacts.findByCustomerId(customer.getId()).stream()
                .map(PartyContactDto::from)
                .toList();
    }

    @Override
    public PartyContactDto updateContact(String customerUid, String contactUid,
                                         UpdatePartyContactRequest req) {
        Customer customer = requireCustomer(customerUid);
        CustomerContact contact = requireContact(contactUid, customer.getId());
        if (req.isPrimary() && !contact.isPrimary()) {
            clearPrimaryContact(customer.getId());
        }
        contact.setContactPerson(req.contactPerson());
        contact.setPhone(req.phone());
        contact.setEmail(req.email());
        contact.setPrimary(req.isPrimary());
        contact.setUpdatedAt(Instant.now());
        contact.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CUSTOMER_CONTACT_UPDATE, "customer_contacts",
                contact.getId(), contact.getUid()));
        return PartyContactDto.from(contact);
    }

    @Override
    public void deactivateContact(String customerUid, String contactUid) {
        Customer customer = requireCustomer(customerUid);
        CustomerContact contact = requireContact(contactUid, customer.getId());
        contact.setStatus(MasterStatus.INACTIVE);
        contact.setPrimary(false);
        contact.setUpdatedAt(Instant.now());
        contact.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_CONTACT_DEACTIVATE, "customer_contacts",
                contact.getId(), contact.getUid()));
    }

    // =========================================================================
    // ADDRESSES
    // =========================================================================

    @Override
    public PartyAddressDto addAddress(String customerUid, CreatePartyAddressRequest req) {
        Customer customer = requireCustomer(customerUid);
        if (req.isDefault()) {
            clearDefaultAddress(customer.getId(), req.addressRole());
        }
        CustomerAddress address = new CustomerAddress(customer.getCompanyId(), customer.getId(),
                req.addressRole(), actorId());
        applyAddressFields(address, req.isDefault(), req.country(), req.addressLine1(),
                req.addressLine2(), req.city(), req.region(), req.postalCode());

        CustomerAddress saved = addresses.save(address);
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_ADDRESS_ADD, "customer_addresses",
                        saved.getId(), saved.getUid())
                .detail(Map.of("customerId", customer.getId(), "role", req.addressRole().name())));
        return PartyAddressDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyAddressDto> listAddresses(String customerUid) {
        Customer customer = requireCustomer(customerUid);
        return addresses.findByCustomerId(customer.getId()).stream()
                .map(PartyAddressDto::from)
                .toList();
    }

    @Override
    public PartyAddressDto updateAddress(String customerUid, String addressUid,
                                          UpdatePartyAddressRequest req) {
        Customer customer = requireCustomer(customerUid);
        CustomerAddress address = requireAddress(addressUid, customer.getId());
        if (req.isDefault() && !address.isDefault()) {
            clearDefaultAddress(customer.getId(), req.addressRole());
        }
        address.setAddressRole(req.addressRole());
        applyAddressFields(address, req.isDefault(), req.country(), req.addressLine1(),
                req.addressLine2(), req.city(), req.region(), req.postalCode());
        address.setUpdatedAt(Instant.now());
        address.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CUSTOMER_ADDRESS_UPDATE, "customer_addresses",
                address.getId(), address.getUid()));
        return PartyAddressDto.from(address);
    }

    @Override
    public void deactivateAddress(String customerUid, String addressUid) {
        Customer customer = requireCustomer(customerUid);
        CustomerAddress address = requireAddress(addressUid, customer.getId());
        address.setStatus(MasterStatus.INACTIVE);
        address.setDefault(false);
        address.setUpdatedAt(Instant.now());
        address.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_ADDRESS_DEACTIVATE, "customer_addresses",
                address.getId(), address.getUid()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Customer requireCustomer(String uid) {
        Customer c = Lookups.orNotFound(customers.findByUid(uid), "Customer", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        return c;
    }

    private CustomerContact requireContact(String contactUid, Long customerId) {
        CustomerContact c = Lookups.orNotFound(contacts.findByUid(contactUid), "CustomerContact", contactUid);
        if (!c.getCustomerId().equals(customerId)) {
            throw new NotFoundException("Customer contact not found.");
        }
        return c;
    }

    private CustomerAddress requireAddress(String addressUid, Long customerId) {
        CustomerAddress a = Lookups.orNotFound(addresses.findByUid(addressUid), "CustomerAddress", addressUid);
        if (!a.getCustomerId().equals(customerId)) {
            throw new NotFoundException("Customer address not found.");
        }
        return a;
    }

    /** Clears the current primary contact for the given customer (if one exists). */
    private void clearPrimaryContact(Long customerId) {
        contacts.findByCustomerIdAndIsPrimaryTrue(customerId).ifPresent(existing -> {
            existing.setPrimary(false);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId());
        });
    }

    /** Clears the current default address for the given (customer, role) pair (if one exists). */
    private void clearDefaultAddress(Long customerId, AddressRole role) {
        addresses.findByCustomerIdAndAddressRoleAndIsDefaultTrue(customerId, role).ifPresent(existing -> {
            existing.setDefault(false);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId());
        });
    }

    private static void applyAddressFields(CustomerAddress a, boolean isDefault, String country,
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
