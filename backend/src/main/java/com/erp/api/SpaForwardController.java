package com.erp.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the co-located Angular SPA (QA single-container deployment). Angular uses
 * client-side (HTML5) routing, so a deep link like {@code /login} or {@code /admin/products}
 * must return {@code index.html} (the SPA boots and routes client-side) rather than 404.
 *
 * <p>This forwards any non-file, non-API path to {@code /index.html}. It deliberately does NOT
 * match {@code /api/**} (those are REST endpoints, handled by @RestController and gated by
 * security) nor paths containing a dot (static files like {@code app.js}, served directly from
 * the classpath {@code static/}). Spring resolves the most specific handler first, so real API
 * routes and static resources are never shadowed by this fallback.
 */
@Controller
public class SpaForwardController {

    /**
     * Forward SPA client-side routes to index.html.
     * Pattern: one or more path segments that contain no '.' (so it never catches static files
     * like {@code main-ABC.js}); the negative lookahead excludes {@code /api/...} and {@code /actuator/...}.
     */
    @GetMapping(value = {"/{path:^(?!api|actuator)[^.]*}", "/{path:^(?!api|actuator)[^.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
