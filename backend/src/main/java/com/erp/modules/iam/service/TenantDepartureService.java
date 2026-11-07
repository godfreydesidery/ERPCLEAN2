package com.erp.modules.iam.service;

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
}
