package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.CustomerAddress;
import com.erp.modules.parties.domain.entity.CustomerContact;
import com.erp.modules.parties.domain.enums.AddressRole;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerAddressRepository;
import com.erp.modules.parties.repository.CustomerContactRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CustomerContactAddressServiceImpl} covering:
 * <ul>
 *   <li>Adding a primary contact clears the previous primary.</li>
 *   <li>Adding a non-primary contact does NOT touch an existing primary.</li>
 *   <li>Adding a default address clears the previous default for the same role.</li>
 *   <li>Adding a default address does NOT clear the default for a different role.</li>
 *   <li>Deactivating a contact sets status=INACTIVE and clears isPrimary.</li>
 * </ul>
 */
class CustomerContactAddressServiceImplTest {

    private CustomerRepository         customerRepo;
    private CustomerContactRepository  contactRepo;
    private CustomerAddressRepository  addressRepo;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private CustomerContactAddressServiceImpl service;

    private static final Long   COMPANY_ID   = 1L;
    private static final Long   CUSTOMER_ID  = 10L;
    private static final String CUSTOMER_UID = "01CUSTUID0000000000000TEST";

    @BeforeEach
    void setUp() {
        customerRepo = mock(CustomerRepository.class);
        contactRepo  = mock(CustomerContactRepository.class);
        addressRepo  = mock(CustomerAddressRepository.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        service = new CustomerContactAddressServiceImpl(
                customerRepo, contactRepo, addressRepo, scopeGuard, audit);

        // Stub the customer lookup
        Customer customer = new Customer(COMPANY_ID, "CUST-0001", PartyType.BUSINESS,
                "Acme Ltd", CustomerKind.CREDIT_ACCOUNT, null);
        // Use reflection to set id (UidEntity id is private final)
        setId(customer, CUSTOMER_ID);
        setUidViaProtected(customer, CUSTOMER_UID);

        when(customerRepo.findByUid(CUSTOMER_UID)).thenReturn(Optional.of(customer));
        // scopeGuard is a no-op mock — assertCanActIn does nothing by default
        RequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // CONTACT TESTS
    // =========================================================================

    @Test
    void addContact_nonPrimary_doesNotClearExistingPrimary() {
        CreatePartyContactRequest req = new CreatePartyContactRequest("Alice", "0700", "a@x.com", false);
        CustomerContact saved = stubContactSave();

        service.addContact(CUSTOMER_UID, req);

        // should never check for existing primary
        verify(contactRepo, never()).findByCustomerIdAndIsPrimaryTrue(any());
        verify(contactRepo).save(any(CustomerContact.class));
    }

    @Test
    void addContact_primary_clearsPreviousPrimary() {
        CreatePartyContactRequest req = new CreatePartyContactRequest("Bob", "0711", "b@x.com", true);

        // existing primary contact
        CustomerContact existing = new CustomerContact(COMPANY_ID, CUSTOMER_ID, null);
        existing.setPrimary(true);
        when(contactRepo.findByCustomerIdAndIsPrimaryTrue(CUSTOMER_ID)).thenReturn(Optional.of(existing));

        CustomerContact saved = stubContactSave();

        service.addContact(CUSTOMER_UID, req);

        assertThat(existing.isPrimary()).isFalse();
        verify(contactRepo).save(any(CustomerContact.class));
    }

    @Test
    void addContact_primary_noExistingPrimary_stillSucceeds() {
        CreatePartyContactRequest req = new CreatePartyContactRequest("Carol", "0722", "c@x.com", true);
        when(contactRepo.findByCustomerIdAndIsPrimaryTrue(CUSTOMER_ID)).thenReturn(Optional.empty());
        stubContactSave();

        PartyContactDto dto = service.addContact(CUSTOMER_UID, req);

        assertThat(dto).isNotNull();
        verify(contactRepo).save(any(CustomerContact.class));
    }

    @Test
    void deactivateContact_setsInactiveAndClearsPrimary() {
        String contactUid = "01CONTACTUID000000000TEST1";
        CustomerContact contact = new CustomerContact(COMPANY_ID, CUSTOMER_ID, null);
        contact.setPrimary(true);
        setId(contact, 99L);
        setUidViaProtected(contact, contactUid);

        when(contactRepo.findByUid(contactUid)).thenReturn(Optional.of(contact));

        service.deactivateContact(CUSTOMER_UID, contactUid);

        assertThat(contact.getStatus()).isEqualTo(MasterStatus.INACTIVE);
        assertThat(contact.isPrimary()).isFalse();
    }

    // =========================================================================
    // ADDRESS TESTS
    // =========================================================================

    @Test
    void addAddress_nonDefault_doesNotClearExistingDefault() {
        CreatePartyAddressRequest req = new CreatePartyAddressRequest(
                AddressRole.SHIP_TO, false, "TZ", "1 Main St", null, "DSM", null, null);
        stubAddressSave();

        service.addAddress(CUSTOMER_UID, req);

        verify(addressRepo, never()).findByCustomerIdAndAddressRoleAndIsDefaultTrue(any(), any());
        verify(addressRepo).save(any(CustomerAddress.class));
    }

    @Test
    void addAddress_default_clearsPreviousDefaultForSameRole() {
        CreatePartyAddressRequest req = new CreatePartyAddressRequest(
                AddressRole.SHIP_TO, true, "TZ", "2 New Rd", null, "DSM", null, null);

        CustomerAddress existing = new CustomerAddress(COMPANY_ID, CUSTOMER_ID, AddressRole.SHIP_TO, null);
        existing.setDefault(true);
        when(addressRepo.findByCustomerIdAndAddressRoleAndIsDefaultTrue(CUSTOMER_ID, AddressRole.SHIP_TO))
                .thenReturn(Optional.of(existing));
        stubAddressSave();

        service.addAddress(CUSTOMER_UID, req);

        assertThat(existing.isDefault()).isFalse();
        verify(addressRepo).save(any(CustomerAddress.class));
    }

    @Test
    void addAddress_defaultShipTo_doesNotClearBillToDefault() {
        CreatePartyAddressRequest req = new CreatePartyAddressRequest(
                AddressRole.SHIP_TO, true, "TZ", "3 Port Ave", null, "TNG", null, null);

        // SHIP_TO has no existing default
        when(addressRepo.findByCustomerIdAndAddressRoleAndIsDefaultTrue(CUSTOMER_ID, AddressRole.SHIP_TO))
                .thenReturn(Optional.empty());
        stubAddressSave();

        service.addAddress(CUSTOMER_UID, req);

        // BILL_TO query never called
        verify(addressRepo, never())
                .findByCustomerIdAndAddressRoleAndIsDefaultTrue(CUSTOMER_ID, AddressRole.BILL_TO);
        verify(addressRepo).save(any(CustomerAddress.class));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CustomerContact stubContactSave() {
        CustomerContact saved = new CustomerContact(COMPANY_ID, CUSTOMER_ID, null);
        setId(saved, 50L);
        setUidViaProtected(saved, "01SAVEDCONTACT00000000001");
        when(contactRepo.save(any(CustomerContact.class))).thenReturn(saved);
        return saved;
    }

    private CustomerAddress stubAddressSave() {
        CustomerAddress saved = new CustomerAddress(COMPANY_ID, CUSTOMER_ID, AddressRole.SHIP_TO, null);
        setId(saved, 60L);
        setUidViaProtected(saved, "01SAVEDADDRESS00000000001");
        when(addressRepo.save(any(CustomerAddress.class))).thenReturn(saved);
        return saved;
    }

    /** Reflectively set the private Long id field inherited from UidEntity. */
    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Use the protected setUid method from UidEntity. */
    private static void setUidViaProtected(com.erp.platform.common.domain.UidEntity entity, String uid) {
        try {
            java.lang.reflect.Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(entity, uid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
