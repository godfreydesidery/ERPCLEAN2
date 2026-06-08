package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlConfigDto;
import com.erp.modules.gl.domain.dto.SetGlConfigRequest;
import java.util.List;

public interface GlConfigService {

    GlConfigDto set(SetGlConfigRequest req);

    List<GlConfigDto> list(Long companyId);

    GlConfigDto getByUid(String uid);

    /** Seeds default gl_configs mappings for a new company. Idempotent. */
    void seedDefaults(Long companyId);
}
