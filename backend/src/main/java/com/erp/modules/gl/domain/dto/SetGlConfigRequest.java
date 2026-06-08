package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.GlConfigKey;

/** Request to set/update a gl_configs mapping for a company. */
public record SetGlConfigRequest(
        String companyUid,
        GlConfigKey configKey,
        String accountUid
) {}
