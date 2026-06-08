package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.AccountDto;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.dto.UpdateAccountRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChartOfAccountService {

    AccountDto create(CreateAccountRequest req);

    AccountDto getByUid(String uid);

    Page<AccountDto> list(Long companyId, Pageable pageable);

    AccountDto update(String uid, UpdateAccountRequest req);

    /** Deactivates the account. Throws if already inactive. */
    AccountDto deactivate(String uid);

    /** Deletes only if the account has no postings (BR-GL-07). Otherwise reject. */
    void delete(String uid);

    /** Seeds the standard TZ CoA for a new company (FR-GL-02). Idempotent. */
    void seedDefaults(Long companyId);
}
