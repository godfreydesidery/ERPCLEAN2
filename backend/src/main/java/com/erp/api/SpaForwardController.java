package com.erp.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the co-located Angular SPA (QA single-container deployment). Angular uses
 * client-side (HTML5) routing, so a deep link like {@code /login} or {@code /admin/products}
 * must return {@code index.html} (the SPA boots and routes client-side) rather than 404.
 *
 * <p>It forwards only "route-like" GETs to index.html and leaves everything else to Spring's
 * default handlers:
 * <ul>
 *   <li>{@code /api/**} and {@code /actuator/**} → not matched (REST endpoints / management).</li>
 *   <li>Static files (any path whose last segment contains a {@code .} — {@code app.js},
 *       {@code media/bootstrap-icons-*.woff2}, {@code favicon.ico}, …) → not forwarded, so the
 *       classpath {@code static/} resource handler serves the real bytes. This is the fix for the
 *       earlier bug where {@code /media/*.woff2} fonts were wrongly forwarded to index.html, which
 *       broke Bootstrap-Icons glyphs.</li>
 * </ul>
 */
@Controller
public class SpaForwardController {

    /** Low-priority catch-all; the in-method check decides whether to actually forward. */
    @GetMapping("/**")
    public String forward(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Leave API + management + the SPA shell itself to their own handlers.
        if (path.startsWith("/api/") || path.startsWith("/actuator/")
                || path.equals("/index.html")) {
            return "forward:/index.html"; // index.html resolves to the static resource directly
        }

        // A dot in the LAST segment means a file (foo.js, icons.woff2, favicon.ico) — let the
        // static resource handler serve it; do NOT forward to index.html.
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            // Returning the file path lets Spring's resource resolver try to serve it; if absent
            // it 404s (correct — a missing asset should not masquerade as the SPA).
            return "forward:" + path;
        }

        // Route-like path (no extension): hand to the SPA shell for client-side routing.
        return "forward:/index.html";
    }
}
