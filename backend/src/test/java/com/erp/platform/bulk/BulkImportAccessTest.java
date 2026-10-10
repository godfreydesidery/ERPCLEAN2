package com.erp.platform.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.platform.bulk.BulkImportService.EntityDescriptor;
import com.erp.platform.security.PermissionResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LRB-07: the import-type picker lists only the types the caller holds the import code for. */
class BulkImportAccessTest {

    private final BulkImportService service = mock(BulkImportService.class);
    private final PermissionResolver resolver = mock(PermissionResolver.class);
    private final BulkImportAccess access = new BulkImportAccess(service, resolver);

    @Test
    void storekeeperWithOnlyStockImportSeesOnlyTheStockType() {
        when(service.entities()).thenReturn(List.of(
                new EntityDescriptor("customers", "Customers", "CUSTOMER.IMPORT"),
                new EntityDescriptor("products", "Products", "PRODUCT.IMPORT"),
                new EntityDescriptor("stock", "Stock on-hand levels", "STOCK.IMPORT")));
        when(resolver.hasPermission(any(), eq("STOCK.IMPORT"), anyLong())).thenReturn(true);

        assertThat(access.importableEntities()).extracting(EntityDescriptor::key)
                .containsExactly("stock");
        assertThat(access.canImportAny()).isTrue();
    }

    @Test
    void callerWithNoImportCodeSeesNothing() {
        when(service.entities()).thenReturn(List.of(
                new EntityDescriptor("customers", "Customers", "CUSTOMER.IMPORT")));

        assertThat(access.importableEntities()).isEmpty();
        assertThat(access.canImportAny()).isFalse();
    }
}
