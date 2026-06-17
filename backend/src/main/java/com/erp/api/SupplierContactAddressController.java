package com.erp.api;

import com.erp.modules.parties.domain.dto.CreatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.CreatePartyContactRequest;
import com.erp.modules.parties.domain.dto.PartyAddressDto;
import com.erp.modules.parties.domain.dto.PartyContactDto;
import com.erp.modules.parties.domain.dto.UpdatePartyAddressRequest;
import com.erp.modules.parties.domain.dto.UpdatePartyContactRequest;
import com.erp.modules.parties.service.SupplierContactAddressService;
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
 * Supplier contacts and addresses sub-resource (ADR-0040 D-3).
 * URL pattern: /api/v1/suppliers/uid/{supplierUid}/contacts  and  .../addresses
 */
@RestController
@RequestMapping("/api/v1/suppliers/uid/{supplierUid}")
public class SupplierContactAddressController {

    private final SupplierContactAddressService service;

    public SupplierContactAddressController(SupplierContactAddressService service) {
        this.service = service;
    }

    // ---- contacts ----

    @GetMapping("/contacts")
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public List<PartyContactDto> listContacts(@PathVariable String supplierUid) {
        return service.listContacts(supplierUid);
    }

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public PartyContactDto addContact(@PathVariable String supplierUid,
                                      @Valid @RequestBody CreatePartyContactRequest req) {
        return service.addContact(supplierUid, req);
    }

    @PutMapping("/contacts/uid/{contactUid}")
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public PartyContactDto updateContact(@PathVariable String supplierUid,
                                         @PathVariable String contactUid,
                                         @Valid @RequestBody UpdatePartyContactRequest req) {
        return service.updateContact(supplierUid, contactUid, req);
    }

    @DeleteMapping("/contacts/uid/{contactUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public void deactivateContact(@PathVariable String supplierUid,
                                   @PathVariable String contactUid) {
        service.deactivateContact(supplierUid, contactUid);
    }

    // ---- addresses ----

    @GetMapping("/addresses")
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public List<PartyAddressDto> listAddresses(@PathVariable String supplierUid) {
        return service.listAddresses(supplierUid);
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public PartyAddressDto addAddress(@PathVariable String supplierUid,
                                       @Valid @RequestBody CreatePartyAddressRequest req) {
        return service.addAddress(supplierUid, req);
    }

    @PutMapping("/addresses/uid/{addressUid}")
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public PartyAddressDto updateAddress(@PathVariable String supplierUid,
                                          @PathVariable String addressUid,
                                          @Valid @RequestBody UpdatePartyAddressRequest req) {
        return service.updateAddress(supplierUid, addressUid, req);
    }

    @DeleteMapping("/addresses/uid/{addressUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public void deactivateAddress(@PathVariable String supplierUid,
                                   @PathVariable String addressUid) {
        service.deactivateAddress(supplierUid, addressUid);
    }
}
