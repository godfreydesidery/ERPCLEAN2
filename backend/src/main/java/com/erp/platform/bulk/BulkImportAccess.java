package com.erp.platform.bulk;

import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import org.springframework.stereotype.Component;

/**
 * SpEL gate for the generic bulk-import endpoints. The endpoints are generic ({@code /bulk/{key}/…})
 * so the required permission varies by entity; this bean resolves the handler for {@code key} and
 * checks its {@link BulkImportHandler#permissionCode()} against the caller. Referenced from
 * {@code @PreAuthorize("@bulkAccess.canImport(#key)")}, which also satisfies the deny-by-default
 * coverage gate ({@code EndpointAuthorizationTest}). An unknown key returns {@code false} (403).
 */
@Component("bulkAccess")
public class BulkImportAccess {

    private final BulkImportService bulkImportService;
    private final PermissionResolver permissionResolver;

    public BulkImportAccess(BulkImportService bulkImportService, PermissionResolver permissionResolver) {
        this.bulkImportService = bulkImportService;
        this.permissionResolver = permissionResolver;
    }

    /** True if the caller may import the given entity type. */
    public boolean canImport(String key) {
        BulkImportHandler h;
        try {
            h = bulkImportService.handler(key);
        } catch (RuntimeException e) {
            return false;
        }
        return permissionResolver.hasPermission(
                RequestContext.get(), h.permissionCode(), System.currentTimeMillis());
    }

    /** True if the caller may import at least one entity type — gates the entity-listing endpoint. */
    public boolean canImportAny() {
        long now = System.currentTimeMillis();
        RequestContext.Principal principal = RequestContext.get();
        return bulkImportService.entities().stream()
                .anyMatch(e -> permissionResolver.hasPermission(principal, e.permissionCode(), now));
    }
}
