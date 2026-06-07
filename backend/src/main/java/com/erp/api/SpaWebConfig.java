package com.erp.api;

import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the co-located Angular SPA (QA single-container) with HTML5-routing fallback.
 *
 * <p>The Angular build is bundled into {@code classpath:/static/}. This registers a resource
 * handler for all paths that:
 * <ul>
 *   <li>returns the real file when it exists (so {@code app.js}, {@code styles.css},
 *       {@code media/bootstrap-icons-*.woff2}, {@code favicon.svg}/{@code .ico} are served as
 *       genuine static bytes with correct content-types), and</li>
 *   <li>falls back to {@code index.html} for any other path (a client-side route like
 *       {@code /login} or {@code /admin/products}) — but NOT for {@code /api/**} or
 *       {@code /actuator/**}, which are left to their REST/management handlers.</li>
 * </ul>
 *
 * <p>This replaces the earlier forward-controller approach, which forwarded static files to
 * index.html (breaking Bootstrap-Icons fonts) and could loop on {@code /index.html} (500s).
 * The resource resolver runs at the framework level, after dispatcher mappings, so it never
 * shadows real {@code @RestController} routes.
 */
@Component
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath,
                                                   @NonNull Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested; // real static file (js/css/font/favicon/...)
                        }
                        // API + management are never SPA routes — don't fall back to index.html.
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        // Client-side route → serve the SPA shell.
                        return new org.springframework.core.io.ClassPathResource("static/index.html");
                    }
                });
    }
}
