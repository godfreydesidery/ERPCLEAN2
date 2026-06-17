package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;

/**
 * Request DTO to update a chart-of-accounts entry.
 *
 * <p>{@code controlType}: changing requires GL.MANAGE permission (enforced in service).
 * Set to null to clear the control-type designation on an account (rare; treat with care).
 */
public record UpdateAccountRequest(
        String name,
        AccountType accountType,
        Boolean active,
        Boolean allowManualPosting,
        /** Optional: stamp or change the control-type classification (D-1, ADR-0040). */
        ControlType controlType
) {}
