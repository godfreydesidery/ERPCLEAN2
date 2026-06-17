package com.erp.api;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import com.erp.modules.parties.service.CustomerContactAddressService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer contacts and addresses sub-resource (ADR-0040 D-3).
 * URL pattern: /api/v1/customers/uid/{customerUid}/contacts  and  .../addresses
 */
@RestController
@RequestMapping("/api/v1/customers/uid/{customerUid}")
public class CustomerContactAddressController {

    private final CustomerContactAddressService service;

    public CustomerContactAddressController(CustomerContactAddressService service) {
        this.service = service;
    }

    // ---- contacts ----

    @GetMapping("/contacts")
    @PreAuthorize("@perm.has('CUSTOMER.VIEW')")
    public List<PartyContactDto> listContacts(@PathVariable String customerUid) {
        return service.listContacts(customerUid);
    }

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public PartyContactDto addContact(@PathVariable String customerUid,
                                      @Valid @RequestBody CreatePartyContactRequest req) {
        return service.addContact(customerUid, req);
    }

    @PutMapping("/contacts/uid/{contactUid}")
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public PartyContactDto updateContact(@PathVariable String customerUid,
                                         @PathVariable String contactUid,
                                         @Valid @RequestBody UpdatePartyContactRequest req) {
        return service.updateContact(customerUid, contactUid, req);
    }

    @DeleteMapping("/contacts/uid/{contactUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public void deactivateContact(@PathVariable String customerUid,
                                   @PathVariable String contactUid) {
        service.deactivateContact(customerUid, contactUid);
    }

    // ---- addresses ----

    @GetMapping("/addresses")
    @PreAuthorize("@perm.has('CUSTOMER.VIEW')")
    public List<PartyAddressDto> listAddresses(@PathVariable String customerUid) {
        return service.listAddresses(customerUid);
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public PartyAddressDto addAddress(@PathVariable String customerUid,
                                       @Valid @RequestBody CreatePartyAddressRequest req) {
        return service.addAddress(customerUid, req);
    }

    @PutMapping("/addresses/uid/{addressUid}")
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public PartyAddressDto updateAddress(@PathVariable String customerUid,
                                          @PathVariable String addressUid,
                                          @Valid @RequestBody UpdatePartyAddressRequest req) {
        return service.updateAddress(customerUid, addressUid, req);
    }

    @DeleteMapping("/addresses/uid/{addressUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('CUSTOMER.MANAGE')")
    public void deactivateAddress(@PathVariable String customerUid,
                                   @PathVariable String addressUid) {
        service.deactivateAddress(customerUid, addressUid);
    }
}
