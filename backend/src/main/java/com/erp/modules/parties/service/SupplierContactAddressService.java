package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import java.util.List;

/**
 * Contact and address management for the supplier party kind (ADR-0040 D-3).
 *
 * <p>Same primary/default semantics as {@link CustomerContactAddressService}.
 */
public interface SupplierContactAddressService {

    // --- contacts ---

    PartyContactDto addContact(String supplierUid, CreatePartyContactRequest req);

    List<PartyContactDto> listContacts(String supplierUid);

    PartyContactDto updateContact(String supplierUid, String contactUid, UpdatePartyContactRequest req);

    void deactivateContact(String supplierUid, String contactUid);

    // --- addresses ---

    PartyAddressDto addAddress(String supplierUid, CreatePartyAddressRequest req);

    List<PartyAddressDto> listAddresses(String supplierUid);

    PartyAddressDto updateAddress(String supplierUid, String addressUid, UpdatePartyAddressRequest req);

    void deactivateAddress(String supplierUid, String addressUid);
}
