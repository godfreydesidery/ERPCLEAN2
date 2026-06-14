package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.api.SupplierQuoteController;
import com.erp.modules.purchases.domain.dto.CaptureSupplierQuoteRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Regression test — Bug #10: supplier-quote capture → 400 on every call.
 *
 * <p>Root cause: {@code SupplierQuoteController.capture()} was gated with
 * {@code @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.QUOTE.CREATE')")}.
 * {@code CaptureSupplierQuoteRequest} has no {@code companyUid()} accessor — the SpEL expression
 * failed with a PropertyAccessException → Spring Security treated the evaluation failure as a
 * denial → every capture call returned 400 (misrouted as a bad-request).
 *
 * <p>Fix: gate changed to {@code @perm.has('PURCHASE.QUOTE.CREATE')}. Tenant-scope is enforced
 * in the service layer ({@code scopeGuard.assertCanActIn(ctx, rfq.getCompanyId())}), which is the
 * correct locus since the RFQ (not the request body) owns the company reference.
 *
 * <p>This test asserts the annotation value so the regression cannot be silently re-introduced.
 */
class SupplierQuoteControllerPermTest {

    @Test
    void captureEndpoint_usesPermHas_notScopedOnMissingAccessor() throws NoSuchMethodException {
        Method capture = SupplierQuoteController.class.getDeclaredMethod(
                "capture", CaptureSupplierQuoteRequest.class);

        PreAuthorize annotation = capture.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("capture() must carry @PreAuthorize")
                .isNotNull();

        String value = annotation.value();
        assertThat(value)
                .as("capture() must use @perm.has (companyUid not on the request body; scope is in the service)")
                .contains("@perm.has");
        assertThat(value)
                .as("capture() must NOT call .companyUid() on the request DTO (it has no such accessor)")
                .doesNotContain("companyUid");
    }
}
