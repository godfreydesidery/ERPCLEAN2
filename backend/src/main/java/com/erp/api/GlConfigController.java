package com.erp.api;

import com.erp.modules.gl.domain.dto.GlConfigDto;
import com.erp.modules.gl.domain.dto.SetGlConfigRequest;
import com.erp.modules.gl.service.GlConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GL posting-role → account configuration controller (ADR-0013, FR-GL-15).
 * Permission codes: GL.VIEW, GL.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/gl/configs")
public class GlConfigController {

    private final GlConfigService service;

    public GlConfigController(GlConfigService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','GL.MANAGE')")
    public GlConfigDto set(@Valid @RequestBody SetGlConfigRequest req) {
        return service.set(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'glconfig','GL.VIEW')")
    public GlConfigDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public List<GlConfigDto> list(@RequestParam Long companyId) {
        return service.list(companyId);
    }
}
