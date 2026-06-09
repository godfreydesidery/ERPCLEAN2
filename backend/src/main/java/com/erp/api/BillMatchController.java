package com.erp.api;

import com.erp.modules.ap.domain.dto.AcceptVarianceRequest;
import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.service.BillMatchService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3-way match trigger + variance acceptance (ADR-0015 D-3/D-4/D-6/D-12).
 * POST /run-match → runs the 3-way match; if all lines pass, posts to GL synchronously.
 * POST /accept-variance → accepts a held variance; if last variance accepted, posts to GL.
 * Permission: AP.BILL.MATCH.
 */
@RestController
@RequestMapping("/api/v1/ap/supplier-bills/uid/{billUid}/match")
public class BillMatchController {

    private final BillMatchService service;

    public BillMatchController(BillMatchService service) {
        this.service = service;
    }

    @PostMapping("/run")
    @PreAuthorize("@perm.scoped(#billUid,'supplierbill','AP.BILL.MATCH')")
    public BillMatchResultDto runMatch(@PathVariable String billUid) {
        return service.runMatch(billUid);
    }

    @PostMapping("/accept-variance")
    @PreAuthorize("@perm.scoped(#billUid,'supplierbill','AP.BILL.MATCH')")
    public BillMatchResultDto acceptVariance(
            @PathVariable String billUid,
            @Valid @RequestBody AcceptVarianceRequest req) {
        return service.acceptVariance(billUid, req.billLineUid());
    }
}
