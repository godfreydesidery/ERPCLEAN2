package com.erp.api;

import com.erp.platform.bulk.BulkImportService;
import com.erp.platform.bulk.BulkImportService.EntityDescriptor;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportReport;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Generic bulk master-data import (mass operations): download a per-entity Excel template, upload it
 * to validate (dry-run preview, nothing written), then commit. The entity is a path key
 * ({@code products}, {@code customers}, {@code suppliers}, {@code prices}); each is gated on its own
 * import permission via {@code @bulkAccess} (deny-by-default). Every row goes through the module's
 * own create/update service, so tenant scope, validation and audit are unchanged.
 */
@RestController
@RequestMapping("/api/v1/bulk")
public class BulkImportController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final BulkImportService bulk;

    public BulkImportController(BulkImportService bulk) {
        this.bulk = bulk;
    }

    /** The entity types the caller can import (for the picker). */
    @GetMapping("/entities")
    @PreAuthorize("@bulkAccess.canImportAny()")
    public List<EntityDescriptor> entities() {
        return bulk.entities();
    }

    /** Download the fill-in Excel template for an entity, scoped to the active company. */
    @GetMapping("/{key}/template")
    @PreAuthorize("@bulkAccess.canImport(#key)")
    public ResponseEntity<byte[]> template(@PathVariable String key) {
        return xlsx(bulk.template(key), key + "-import-template.xlsx");
    }

    /**
     * Download the entity's CURRENT rows pre-filled into the template (the download → edit →
     * re-upload round-trip). Extra query params pass through to the handler (e.g. {@code priceList}
     * for the prices export). Re-uploading the edited file to {@code /commit} applies the changes.
     */
    @GetMapping("/{key}/export")
    @PreAuthorize("@bulkAccess.canImport(#key)")
    public ResponseEntity<byte[]> export(@PathVariable String key,
                                         @RequestParam(required = false) Map<String, String> params) {
        return xlsx(bulk.export(key, params), key + "-export.xlsx");
    }

    /** Dry-run: parse + validate every row, write nothing, return the per-row preview. */
    @PostMapping(path = "/{key}/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@bulkAccess.canImport(#key)")
    public ImportReport validate(@PathVariable String key, @RequestParam("file") MultipartFile file) {
        return bulk.run(key, file, ImportMode.VALIDATE);
    }

    /** Commit: create/update each row through the module service; returns the per-row result. */
    @PostMapping(path = "/{key}/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@bulkAccess.canImport(#key)")
    public ImportReport commit(@PathVariable String key, @RequestParam("file") MultipartFile file) {
        return bulk.run(key, file, ImportMode.COMMIT);
    }

    private static ResponseEntity<byte[]> xlsx(byte[] content, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }
}
