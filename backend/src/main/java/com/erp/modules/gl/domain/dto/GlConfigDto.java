package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.GlConfigKey;

/** Response DTO for a gl_configs mapping entry. */
public record GlConfigDto(
        Long id,
        String uid,
        Long companyId,
        GlConfigKey configKey,
        Long accountId,
        String accountCode,
        String accountName
) {}
