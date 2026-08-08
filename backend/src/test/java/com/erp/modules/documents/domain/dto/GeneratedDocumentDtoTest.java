package com.erp.modules.documents.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.api.DocumentController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The {@code downloadUrl} this DTO advertises must be a route that exists (UAT finding #5).
 *
 * <p>It used to be {@code /api/v1/documents/{uid}/download} while the controller maps
 * {@code /api/v1/documents/uid/{uid}/download} — one segment short — so every link handed to an API
 * client 404'd, for every document type. The web app builds its own URL and never noticed; the POS
 * app and any integrator following the response did.
 *
 * <p>The assertion is derived from the controller's own annotations rather than from a second
 * hard-coded string, so moving the route breaks this test instead of silently breaking callers.
 */
class GeneratedDocumentDtoTest {

    @Test
    void downloadUrl_matchesTheRouteTheControllerActuallyPublishes() throws Exception {
        assertThat(GeneratedDocumentDto.downloadUrlFor("01ABCDEF"))
                .isEqualTo(actualDownloadRoute().replace("{uid}", "01ABCDEF"));
    }

    @Test
    void downloadUrl_isNotTheOldUidLessShapeThat404d() {
        assertThat(GeneratedDocumentDto.downloadUrlFor("01ABCDEF"))
                .isEqualTo("/api/v1/documents/uid/01ABCDEF/download")
                .isNotEqualTo("/api/v1/documents/01ABCDEF/download");
    }

    /** Reads the class-level base path + the {@code download} handler's own mapping. */
    private static String actualDownloadRoute() throws NoSuchMethodException {
        String base = DocumentController.class.getAnnotation(RequestMapping.class).value()[0];
        Method download = DocumentController.class.getMethod("download", String.class);
        String path = download.getAnnotation(GetMapping.class).value()[0];
        return base + path;
    }
}
