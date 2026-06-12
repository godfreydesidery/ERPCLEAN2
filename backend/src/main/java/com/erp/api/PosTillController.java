package com.erp.api;

import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.service.PosTillService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS till CRUD (ADR-0029 D-5).
 * Permissions seeded in V43__pos.sql.
 */
@RestController
@RequestMapping("/api/v1/pos/tills")
public class PosTillController {

    private final PosTillService tillService;

    public PosTillController(PosTillService tillService) {
        this.tillService = tillService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.TILL.MANAGE')")
    public PosTillDto create(@Valid @RequestBody CreatePosTillRequest request) {
        return tillService.createTill(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'postill','POS.TILL.VIEW')")
    public PosTillDto getByUid(@PathVariable String uid) {
        return tillService.getTillByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('POS.TILL.VIEW')")
    public List<PosTillDto> listByBranch(@RequestParam Long companyId,
                                          @RequestParam Long branchId) {
        return tillService.listTillsByBranch(companyId, branchId);
    }

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'postill','POS.TILL.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        tillService.deactivateTill(uid);
    }
}
