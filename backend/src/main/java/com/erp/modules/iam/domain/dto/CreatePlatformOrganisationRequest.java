package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * Confirms creating the platform organisation (owner, 2026-11-07): the exact name of the
 * organisation the operator is leaving, typed by hand.
 */
public record CreatePlatformOrganisationRequest(@Size(max = 160) String confirmName) {
}
