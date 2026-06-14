package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.api.StockValuationController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Regression test — Bug #2: stock valuation report returned 403 for rootadmin.
 *
 * <p>Root cause: {@code StockValuationController.report()} used
 * {@code @PreAuthorize("hasAuthority('INVENTORY.VALUATION.VIEW')")}. Spring Security's
 * {@code hasAuthority} checks the {@code GrantedAuthority} list populated from the JWT
 * {@code scope} claim — root's JWT carries no permission claims, so the check always denied root.
 * The {@code @perm.has()} expression correctly short-circuits for root via
 * {@link com.erp.platform.security.PermissionResolver#hasPermission}.
 *
 * <p>Fix: changed to {@code @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")}.
 * This test asserts that the annotation uses the {@code @perm.has} form so the regression
 * cannot silently be re-introduced.
 */
class StockValuationControllerPermTest {

    @Test
    void reportEndpoint_usesPermHasNotHasAuthority() throws NoSuchMethodException {
        Method report = StockValuationController.class.getDeclaredMethod("report",
                java.time.LocalDate.class);

        PreAuthorize annotation = report.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("report() must carry @PreAuthorize")
                .isNotNull();

        String value = annotation.value();
        assertThat(value)
                .as("report() must use @perm.has (root-bypass aware), not hasAuthority (JWT-claims only)")
                .contains("@perm.has");
        assertThat(value)
                .as("report() must not use hasAuthority (root bypass does NOT work through hasAuthority)")
                .doesNotContain("hasAuthority");
    }

    @Test
    void openingEndpoint_usesPermHasNotHasAuthority() throws NoSuchMethodException {
        Method opening = StockValuationController.class.getDeclaredMethod("setOpening",
                com.erp.modules.stock.domain.dto.SetOpeningValuationRequest.class);

        PreAuthorize annotation = opening.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("setOpening() must carry @PreAuthorize")
                .isNotNull();

        String value = annotation.value();
        assertThat(value)
                .as("setOpening() must use @perm.has (root-bypass aware)")
                .contains("@perm.has");
        assertThat(value)
                .as("setOpening() must not use hasAuthority")
                .doesNotContain("hasAuthority");
    }
}
