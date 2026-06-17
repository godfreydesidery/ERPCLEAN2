package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import java.util.List;

/**
 * Contact and address management for the customer party kind (ADR-0040 D-3).
 *
 * <p>Primary-contact uniqueness: at most one contact per customer may be primary.
 * Setting a new primary automatically clears the previous one.
 *
 * <p>Default-address uniqueness: at most one address per (customer, address_role) may be default.
 * Setting a new default automatically clears the previous one for that role.
 */
public interface CustomerContactAddressService {

    // --- contacts ---

    PartyContactDto addContact(String customerUid, CreatePartyContactRequest req);

    List<PartyContactDto> listContacts(String customerUid);

    PartyContactDto updateContact(String customerUid, String contactUid, UpdatePartyContactRequest req);

    void deactivateContact(String customerUid, String contactUid);

    // --- addresses ---

    PartyAddressDto addAddress(String customerUid, CreatePartyAddressRequest req);

    List<PartyAddressDto> listAddresses(String customerUid);

    PartyAddressDto updateAddress(String customerUid, String addressUid, UpdatePartyAddressRequest req);

    void deactivateAddress(String customerUid, String addressUid);
}
