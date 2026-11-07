package com.erp.modules.iam.domain.dto;

/**
 * Body of {@code POST /api/v1/organisations/uid/{uid}/purge}.
 *
 * @param dryRun      {@code null} or {@code true} (the default) only counts what would be deleted;
 *                    deletion needs an explicit {@code false}
 * @param confirmName the organisation's exact name, required when {@code dryRun} is false
 */
public record PurgeTenantRequestDto(Boolean dryRun, String confirmName) {

    public boolean isDryRun() {
        return dryRun == null || dryRun;
    }
}
