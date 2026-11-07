package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.tenant.TenantExportService;
import com.erp.platform.tenant.TenantRef;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT {@code @Transactional}: the export owns a read-only REPEATABLE READ transaction
 * of its own, and its audit row is written in a separate one after the snapshot has ended.
 */
@Service
public class TenantDepartureServiceImpl implements TenantDepartureService {

    private static final Logger log = LoggerFactory.getLogger(TenantDepartureServiceImpl.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final OrganisationRepository organisations;
    private final TenantExportService exporter;
    private final AuditService audit;
    private final Clock clock;

    public TenantDepartureServiceImpl(OrganisationRepository organisations,
                                      TenantExportService exporter, AuditService audit,
                                      Clock clock) {
        this.organisations = organisations;
        this.exporter = exporter;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public void exportTenant(String uid, ExportSink sink) throws IOException {
        Organisation org = requireTarget(uid);
        TenantRef ref = new TenantRef(org.getId(), org.getUid(), org.getName());

        String label = org.getAlias() != null ? org.getAlias() : "organisation";
        String fileName = "tenant-export-" + label.replaceAll("[^A-Za-z0-9_-]", "_") + "-"
                + STAMP.format(clock.instant()) + ".zip";

        OutputStream out = sink.open(fileName);
        exporter.export(ref, out, result -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("organisationUid", org.getUid());
            detail.put("organisationName", org.getName());
            detail.put("tables", String.valueOf(result.rowCounts().size()));
            detail.put("totalRows", String.valueOf(result.totalRows()));
            detail.put("flywayVersion", result.flywayVersion());
            detail.put("redactedColumns", String.join(",", result.redacted()));
            // Independent (REQUIRES_NEW): the snapshot transaction is read-only and already over.
            audit.recordIndependent(AuditEvent.of(AuditActions.ORG_EXPORT, "organisations",
                    org.getId(), org.getUid()).detail(detail));
        });
        log.info("Tenant export streamed for organisation {} as {}", org.getId(), fileName);
    }

    /**
     * Loads the organisation the operator is acting on. NOT FOUND - never FORBIDDEN - for a caller
     * who is not the platform operator, and for a uid that names nothing, exactly as
     * {@code OrganisationServiceImpl.setStatus} does: the refusal must not reveal that the
     * organisation exists. Unlike setStatus there is no own-organisation exemption: a tenant's
     * administrator has no business exporting or purging even their own tenancy through this path.
     */
    private Organisation requireTarget(String uid) {
        RequestContext.Principal caller = RequestContext.get();
        if (!OrganisationServiceImpl.isPlatformOperator(caller)) {
            throw new NotFoundException("Organisation not found.");
        }
        return organisations.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Organisation not found."));
    }
}
