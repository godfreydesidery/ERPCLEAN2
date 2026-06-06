package com.erp.api;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateOtherPartyRequest;
import com.erp.modules.parties.domain.dto.OtherPartyDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateOtherPartyRequest;
import com.erp.modules.parties.service.OtherPartyService;
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
 * Other/misc party master administration (FR-PARTY-04, ADR-0006 D-10/D-11).
 */
@RestController
@RequestMapping("/api/v1/other-parties")
public class OtherPartyController {

    private final OtherPartyService otherParties;

    public OtherPartyController(OtherPartyService otherParties) {
        this.otherParties = otherParties;
    }

    @GetMapping
    @PreAuthorize("@perm.has('OTHERPARTY.VIEW')")
    public ApiResponse<List<OtherPartyDto>> list(@RequestParam Long companyId,
                                                 @RequestParam(required = false) String q,
                                                 Pageable pageable) {
        Page<OtherPartyDto> page = otherParties.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('OTHERPARTY.VIEW')")
    public OtherPartyDto get(@PathVariable String uid) {
        return otherParties.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('OTHERPARTY.MANAGE')")
    public OtherPartyDto create(@Valid @RequestBody CreateOtherPartyRequest request) {
        return otherParties.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'otherparty','OTHERPARTY.MANAGE')")
    public OtherPartyDto update(@PathVariable String uid,
                                @Valid @RequestBody UpdateOtherPartyRequest request) {
        return otherParties.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'otherparty','OTHERPARTY.MANAGE')")
    public void archive(@PathVariable String uid) {
        otherParties.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'otherparty','OTHERPARTY.MANAGE')")
    public void restore(@PathVariable String uid) {
        otherParties.restoreByUid(uid);
    }

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.has('OTHERPARTY.VIEW')")
    public List<PartyBranchDto> listBranches(@PathVariable String uid) {
        return otherParties.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'otherparty','PARTY.BRANCH.ASSIGN')")
    public PartyBranchDto assignBranch(@PathVariable String uid,
                                       @Valid @RequestBody AssignPartyBranchRequest request) {
        return otherParties.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'otherparty','PARTY.BRANCH.ASSIGN')")
    public void removeBranch(@PathVariable String uid, @PathVariable String branchUid) {
        otherParties.removeBranch(uid, branchUid);
    }
}
