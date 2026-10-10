package com.erp.modules.documents.service;

import com.erp.modules.documents.domain.dto.GeneratedDocumentDto;
import com.erp.modules.documents.domain.dto.RenderDocumentRequest;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Orchestrates rendering a source document to PDF (ADR-0023 D-4, FR-DOC-06..17).
 * Read-only on the source — no GL post, no stock move, no source mutation (NFR-DOC-02).
 */
public interface DocumentRenderService {

    /**
     * Renders a document to PDF, logs the render record, publishes DOCUMENT.GENERATED.
     * Returns the GeneratedDocumentDto (record + download link). Runs in one @Transactional.
     */
    GeneratedDocumentDto render(RenderDocumentRequest req);

    /**
     * Re-renders a previously generated document from the live source and returns the bytes.
     * Does NOT create a new log row (D-4). Used by the download endpoint.
     */
    byte[] download(String generatedDocumentUid);

    /** List the generated-documents log, paged and filtered. */
    Page<GeneratedDocumentDto> list(Long companyId, DocumentType type, String sourceUid,
                                    Instant from, Instant to, Pageable pageable);

    /** Get a single log record by uid. */
    GeneratedDocumentDto getByUid(String uid);

    /**
     * Renders a purchase return / debit note to PDF bytes with the company's document branding.
     * <b>Stream-only</b>: no generated_documents row, no outbox event — the caller streams the bytes.
     * The print model comes from {@code PurchaseReturnService.printByUid}, which has already
     * tenant-checked the caller; the company is re-checked here before its branding is read.
     */
    byte[] renderPurchaseReturn(PurchaseReturnPrintDto printModel);
}
