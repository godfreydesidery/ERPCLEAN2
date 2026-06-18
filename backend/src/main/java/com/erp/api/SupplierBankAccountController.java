package com.erp.api;

import com.erp.modules.parties.domain.dto.CreateSupplierBankAccountRequest;
import com.erp.modules.parties.domain.dto.SupplierBankAccountDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierBankAccountRequest;
import com.erp.modules.parties.service.SupplierService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supplier bank account sub-resource (D-4, ADR-0040).
 * Nested under /api/v1/suppliers/uid/{supplierUid}/bank-accounts.
 * Reuses SUPPLIER.VIEW / SUPPLIER.MANAGE permissions.
 */
@RestController
@RequestMapping("/api/v1/suppliers/uid/{supplierUid}/bank-accounts")
public class SupplierBankAccountController {

    private final SupplierService supplierService;

    public SupplierBankAccountController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('SUPPLIER.VIEW')")
    public List<SupplierBankAccountDto> list(@PathVariable String supplierUid) {
        return supplierService.listBankAccounts(supplierUid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public SupplierBankAccountDto add(@PathVariable String supplierUid,
                                      @Valid @RequestBody CreateSupplierBankAccountRequest request) {
        return supplierService.addBankAccount(supplierUid, request);
    }

    @PutMapping("/uid/{bankAccountUid}")
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public SupplierBankAccountDto update(@PathVariable String supplierUid,
                                         @PathVariable String bankAccountUid,
                                         @Valid @RequestBody UpdateSupplierBankAccountRequest request) {
        return supplierService.updateBankAccount(supplierUid, bankAccountUid, request);
    }

    @PutMapping("/uid/{bankAccountUid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public void archive(@PathVariable String supplierUid, @PathVariable String bankAccountUid) {
        supplierService.archiveBankAccount(supplierUid, bankAccountUid);
    }

    @PutMapping("/uid/{bankAccountUid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public void restore(@PathVariable String supplierUid, @PathVariable String bankAccountUid) {
        supplierService.restoreBankAccount(supplierUid, bankAccountUid);
    }

    @PutMapping("/uid/{bankAccountUid}/set-default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('SUPPLIER.MANAGE')")
    public void setDefault(@PathVariable String supplierUid, @PathVariable String bankAccountUid) {
        supplierService.setDefaultBankAccount(supplierUid, bankAccountUid);
    }
}
