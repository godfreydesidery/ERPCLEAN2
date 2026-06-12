package com.erp.modules.crm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.api.PipelineController;
import com.erp.api.PipelineStageController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Regression guard for the two adversarial-review findings (ADR-0031 D-11):
 *
 * <ul>
 *   <li>Finding 1 [HIGH]: PipelineStageController read endpoints must NOT use the non-existent
 *       permission 'CRM.STAGE.VIEW' — they must use the seeded 'CRM.OPPORTUNITY.VIEW'.</li>
 *   <li>Finding 2 [MEDIUM]: PipelineController analytics endpoints must use the seeded
 *       'CRM.PIPELINE.VIEW', not 'CRM.OPPORTUNITY.VIEW' (which conflates opportunity list
 *       access with pipeline/forecast/KPI analytics).</li>
 * </ul>
 *
 * <p>Pure reflection — no Spring context, no Testcontainers, instant feedback.
 */
class CrmControllerPermissionTest {

    // -------------------------------------------------------------------------
    // Finding 1 [HIGH] — PipelineStageController read endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /crm/pipeline-stages/{uid} must gate on 'CRM.OPPORTUNITY.VIEW', not 'CRM.STAGE.VIEW'
     * (which is not seeded in V51__crm.sql and would return 403 for all legitimate read users).
     */
    @Test
    void pipelineStageController_getByUid_usesCrmOpportunityViewPermission() {
        String expr = preAuthorizeValue(PipelineStageController.class, "getByUid");
        assertThat(expr)
                .as("getByUid must gate on CRM.OPPORTUNITY.VIEW (not the unseeded CRM.STAGE.VIEW)")
                .contains("CRM.OPPORTUNITY.VIEW")
                .doesNotContain("CRM.STAGE.VIEW");
    }

    /**
     * GET /crm/pipeline-stages must gate on 'CRM.OPPORTUNITY.VIEW', not 'CRM.STAGE.VIEW'
     * (which is not seeded in V51__crm.sql and would return 403 for all legitimate read users).
     */
    @Test
    void pipelineStageController_listByCompany_usesCrmOpportunityViewPermission() {
        String expr = preAuthorizeValue(PipelineStageController.class, "listByCompany");
        assertThat(expr)
                .as("listByCompany must gate on CRM.OPPORTUNITY.VIEW (not the unseeded CRM.STAGE.VIEW)")
                .contains("CRM.OPPORTUNITY.VIEW")
                .doesNotContain("CRM.STAGE.VIEW");
    }

    // -------------------------------------------------------------------------
    // Finding 2 [MEDIUM] — PipelineController analytics endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /crm/pipeline must gate on 'CRM.PIPELINE.VIEW', not 'CRM.OPPORTUNITY.VIEW'.
     * Using OPPORTUNITY.VIEW conflates individual opportunity access with pipeline analytics.
     */
    @Test
    void pipelineController_pipeline_usesCrmPipelineViewPermission() {
        String expr = preAuthorizeValue(PipelineController.class, "pipeline");
        assertThat(expr)
                .as("pipeline() must gate on CRM.PIPELINE.VIEW (seeded in V51), not CRM.OPPORTUNITY.VIEW")
                .contains("CRM.PIPELINE.VIEW")
                .doesNotContain("CRM.OPPORTUNITY.VIEW");
    }

    /**
     * GET /crm/pipeline/forecast must gate on 'CRM.PIPELINE.VIEW'.
     */
    @Test
    void pipelineController_forecast_usesCrmPipelineViewPermission() {
        String expr = preAuthorizeValue(PipelineController.class, "forecast");
        assertThat(expr)
                .as("forecast() must gate on CRM.PIPELINE.VIEW (seeded in V51), not CRM.OPPORTUNITY.VIEW")
                .contains("CRM.PIPELINE.VIEW")
                .doesNotContain("CRM.OPPORTUNITY.VIEW");
    }

    /**
     * GET /crm/pipeline/kpis must gate on 'CRM.PIPELINE.VIEW'.
     */
    @Test
    void pipelineController_kpis_usesCrmPipelineViewPermission() {
        String expr = preAuthorizeValue(PipelineController.class, "kpis");
        assertThat(expr)
                .as("kpis() must gate on CRM.PIPELINE.VIEW (seeded in V51), not CRM.OPPORTUNITY.VIEW")
                .contains("CRM.PIPELINE.VIEW")
                .doesNotContain("CRM.OPPORTUNITY.VIEW");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link PreAuthorize#value()} for the FIRST method named {@code methodName}
     * declared in {@code controller}. Fails with a clear message if none is found or the
     * method carries no {@code @PreAuthorize}.
     */
    private static String preAuthorizeValue(Class<?> controller, String methodName) {
        List<Method> matches = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
        assertThat(matches)
                .as("Expected a method named '%s' in %s", methodName, controller.getSimpleName())
                .isNotEmpty();
        Method method = matches.get(0);
        PreAuthorize pa = method.getAnnotation(PreAuthorize.class);
        assertThat(pa)
                .as("Method %s#%s must carry @PreAuthorize", controller.getSimpleName(), methodName)
                .isNotNull();
        return pa.value();
    }
}
