package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deny-by-default gate coverage (ADR-0002, security review). With method security on, a controller
 * handler WITHOUT a {@code @PreAuthorize} is reachable by any authenticated user — a forgotten gate
 * fails OPEN. This test makes that a build failure: every request-mapping handler under
 * {@code com.erp.api} must carry {@code @PreAuthorize}, except the explicitly public endpoints
 * (auth login/refresh/logout, health). Adding a new endpoint without a gate breaks the build.
 */
class EndpointAuthorizationTest {

    /** Public-by-design handlers: "ControllerSimpleName#method". Keep this list short and reviewed. */
    private static final Set<String> PUBLIC_HANDLERS = Set.of(
            "AuthController#login",
            "AuthController#refresh",
            "AuthController#logout",
            "HealthController#health",
            // Catch-all that throws NoHandlerFoundException for unmatched /api/** paths so they return
            // the standard ApiResponse 404 (not Spring's whitelabel + stack trace). Returning 404 on a
            // non-existent path is not a security-sensitive operation, so it is public by design.
            "ApiNotFoundController#apiCatchAll");

    @Test
    void everyApiHandlerIsPermissionGatedOrExplicitlyPublic() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> ungated = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("com.erp.api")) {
            Class<?> controller = load(bean.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!isRequestHandler(method)) {
                    continue;
                }
                String id = controller.getSimpleName() + "#" + method.getName();
                boolean gated = method.isAnnotationPresent(PreAuthorize.class);
                if (!gated && !PUBLIC_HANDLERS.contains(id)) {
                    ungated.add(id);
                }
            }
        }

        assertThat(ungated)
                .as("API handlers missing @PreAuthorize (add a gate, or add to PUBLIC_HANDLERS if "
                        + "intentionally public): %s", ungated)
                .isEmpty();
    }

    private static boolean isRequestHandler(Method method) {
        for (Annotation a : method.getAnnotations()) {
            Class<? extends Annotation> type = a.annotationType();
            if (type.isAnnotationPresent(RequestMapping.class)
                    || type.equals(RequestMapping.class)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load controller " + name, e);
        }
    }
}
