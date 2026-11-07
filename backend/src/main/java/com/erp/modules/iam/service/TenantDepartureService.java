package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.PurgeTenantRequestDto;
import com.erp.modules.iam.domain.dto.TenantPurgeReportDto;
import java.io.IOException;
import java.io.OutputStream;

/**
 * What a departing tenant is owed and what the platform may then do (MULTITENANCY-PLAN §14.2 R-7 /
 * R-8, D-6). Platform operator only: the controller gates on {@code ORG.SUSPEND} (export) and
 * {@code ORG.CREATE} (purge) - both {@code platform}-module codes no tenant administrator can hold -
 * and the service independently refuses any caller who is not the platform operator.
 */
public interface TenantDepartureService {

    /** Where the export archive is written; the controller sets its response headers here. */
    @FunctionalInterface
    interface ExportSink {
        OutputStream open(String fileName) throws IOException;
    }

    /**
     * Streams a zip of every row of the organisation. Validation happens BEFORE {@code sink.open},
     * so a refusal is still a normal JSON error response rather than a corrupt download.
     * Audited as {@code ORG.EXPORT} only once the archive is complete.
     */
    void exportTenant(String uid, ExportSink sink) throws IOException;

    /**
     * Dry-runs (the default) or performs the deletion of a departed tenant (R-8).
     *
     * <p>A real purge is refused unless ALL hold: the organisation is suspended; at least 30 days
     * have passed since its latest {@code ORG.SUSPEND} audit row with no later {@code ORG.RESUME};
     * a completed {@code ORG.EXPORT} was audited after that suspension; it is neither the caller's
     * own organisation nor a platform operator's; and {@code confirmName} is its exact name.
     *
     * <p>A dry run deletes nothing and reports the per-table counts in deletion order plus which of
     * the rules above would currently refuse a real run (it needs no confirmation name, but is still
     * refused outright for the caller's own or a platform organisation).
     */
    TenantPurgeReportDto purgeTenant(String uid, PurgeTenantRequestDto request);
}
