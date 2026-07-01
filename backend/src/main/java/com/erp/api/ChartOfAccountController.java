package com.erp.api;

import com.erp.modules.gl.domain.dto.AccountDto;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.dto.UpdateAccountRequest;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chart of Accounts REST controller (ADR-0013, FR-GL-01..05).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission codes seeded in V10__general_ledger.sql: GL.VIEW, GL.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/gl/accounts")
public class ChartOfAccountController {

    private final ChartOfAccountService service;

    public ChartOfAccountController(ChartOfAccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','GL.MANAGE')")
    public AccountDto create(@Valid @RequestBody CreateAccountRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'account','GL.VIEW')")
    public AccountDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'account','GL.MANAGE')")
    public AccountDto update(@PathVariable String uid,
                             @Valid @RequestBody UpdateAccountRequest req) {
        return service.update(uid, req);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'account','GL.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        service.deactivate(uid);
    }

    // Read-floor (F26): the counter-GL-account picker on the Record-Cash-Entry screen
    // (@perm CASH.ENTRY.RECORD) fires this list on load. A cashier holds CASH.ENTRY.RECORD /
    // CASH.VIEW but not GL.VIEW, so the picker hard-403'd and blanked the screen. GL accounts are
    // financially sensitive, so — mirroring F23 (AP.BILL.ENTER / PURCHASE.RECEIVE) — the gate is
    // broadened only to the adjacent cash verbs whose own screen requires this read, NOT to plain
    // membership. Tenant isolation is unchanged: ChartOfAccountServiceImpl.list keeps
    // scopeGuard.assertCanActIn(ctx, companyId), so any holder reads only their own company's CoA.
    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW') or @perm.has('CASH.ENTRY.RECORD') or @perm.has('CASH.VIEW')")
    public ApiResponse<List<AccountDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<AccountDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
