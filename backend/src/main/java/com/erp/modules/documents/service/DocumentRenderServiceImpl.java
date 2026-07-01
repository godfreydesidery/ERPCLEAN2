package com.erp.modules.documents.service;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.ar.service.ArAgeingQuery;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.ar.service.ArCreditNoteService;
import com.erp.modules.documents.domain.dto.DocumentGeneratedPayload;
import com.erp.modules.documents.domain.dto.GeneratedDocumentDto;
import com.erp.modules.documents.domain.dto.RenderDocumentRequest;
import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.entity.GeneratedDocument;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.render.DocumentPdfRenderer;
import com.erp.modules.documents.render.DocumentRenderModel;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.documents.repository.GeneratedDocumentRepository;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseOrderService;
import com.erp.modules.reporting.export.PdfStatementRenderer;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.service.DeliveryService;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates PDF rendering (ADR-0023 D-4, FR-DOC-06..17).
 * Invariant: read-only on source — no GL post, no stock move, no source mutation (NFR-DOC-02).
 *
 * Source company resolution uses ScopeGuard.companyIdOf(sourceType, sourceUid) — the cross-cutting
 * spine that already holds all source-module repos. This keeps documents.service from holding
 * direct foreign-module repository references (NFR-DOC-05 / ModuleBoundaryTest).
 */
@Service
public class DocumentRenderServiceImpl implements DocumentRenderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRenderServiceImpl.class);
    private static final String DOCUMENT_RENDER = "DOCUMENT.RENDER";

    private final DocumentTemplateRepository  templates;
    private final DocumentBrandingRepository  brandings;
    private final GeneratedDocumentRepository generatedDocs;

    // Source read services (DTO-only — ADR-0023 D-9 / NFR-DOC-05)
    private final SalesInvoiceService  salesInvoiceService;
    private final DeliveryService      deliveryService;
    private final GoodsReceiptService  goodsReceiptService;
    private final PurchaseOrderService purchaseOrderService;
    private final ArCreditNoteService  arCreditNoteService;
    private final ArAgeingQuery        arAgeingQuery;

    private final DocumentNumberGenerator  numberGenerator;
    private final DocumentModelBuilder     modelBuilder;
    private final DocumentPdfRenderer      pdfRenderer;
    private final PdfStatementRenderer     statementPdfRenderer;
    private final ScopeGuard               scopeGuard;
    private final OutboxPublisher          outboxPublisher;
    private final AuditService             audit;
    private final ObjectMapper             objectMapper;

    public DocumentRenderServiceImpl(
            DocumentTemplateRepository templates,
            DocumentBrandingRepository brandings,
            GeneratedDocumentRepository generatedDocs,
            SalesInvoiceService salesInvoiceService,
            DeliveryService deliveryService,
            GoodsReceiptService goodsReceiptService,
            PurchaseOrderService purchaseOrderService,
            ArCreditNoteService arCreditNoteService,
            ArAgeingQuery arAgeingQuery,
            DocumentNumberGenerator numberGenerator,
            DocumentModelBuilder modelBuilder,
            DocumentPdfRenderer pdfRenderer,
            PdfStatementRenderer statementPdfRenderer,
            ScopeGuard scopeGuard,
            OutboxPublisher outboxPublisher,
            AuditService audit,
            ObjectMapper objectMapper) {
        this.templates            = templates;
        this.brandings            = brandings;
        this.generatedDocs        = generatedDocs;
        this.salesInvoiceService  = salesInvoiceService;
        this.deliveryService      = deliveryService;
        this.goodsReceiptService  = goodsReceiptService;
        this.purchaseOrderService = purchaseOrderService;
        this.arCreditNoteService  = arCreditNoteService;
        this.arAgeingQuery        = arAgeingQuery;
        this.numberGenerator      = numberGenerator;
        this.modelBuilder         = modelBuilder;
        this.pdfRenderer          = pdfRenderer;
        this.statementPdfRenderer = statementPdfRenderer;
        this.scopeGuard           = scopeGuard;
        this.outboxPublisher      = outboxPublisher;
        this.audit                = audit;
        this.objectMapper         = objectMapper;
    }

    // -------------------------------------------------------------------------
    // render — full orchestration (ADR-0023 D-4, FR-DOC-06..14)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public GeneratedDocumentDto render(RenderDocumentRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        DocumentType type = req.documentType();

        // 1. Resolve registry row; reject if missing or inactive (FR-DOC-13 / BR-DOC-04)
        Long companyId = principal != null ? principal.companyId() : null;
        DocumentTemplate tmpl = templates.findByCompanyIdAndDocumentType(companyId, type)
                .orElseThrow(() -> {
                    // Technical detail (type + company) goes to the log only; the user gets a
                    // friendly, actionable message free of internal identifiers (error-hygiene).
                    log.warn("No {} document template for company {} — render blocked; run company "
                            + "\"Provision defaults\" to seed the document templates.", type, companyId);
                    return new NotFoundException(
                            "Document templates aren't set up for this company yet. Ask an administrator "
                                    + "to run \"Provision defaults\" for the company, then try again.");
                });
        if (tmpl.getStatus() != MasterStatus.ACTIVE) {
            log.warn("Document template {} for company {} is not ACTIVE — render blocked.", type, companyId);
            throw new IllegalStateException(
                    "This document layout is currently turned off. Ask an administrator to re-enable it, "
                            + "then try again.");
        }

        // 2. Resolve source company via ScopeGuard (NFR-DOC-03 / ADR-0023 D-9)
        Long sourceCompanyId = resolveSourceCompanyId(type, req.sourceUid(), req.sourceParams());
        scopeGuard.assertCanActIn(principal, sourceCompanyId);

        // 3. Load branding (with fallback — BR-DOC-06)
        DocumentBranding branding = brandings.findByCompanyId(sourceCompanyId).orElse(null);

        // 4. Build render model + render to PDF
        RenderResult result = renderBytes(type, req, branding, tmpl.getTitle());

        // 5. Content hash + byte size
        String hash = sha256(result.bytes());

        // 6. Allocate DOC-#### (D-8)
        String docNumber = numberGenerator.next(sourceCompanyId);

        // 7. Insert generated_documents row (append-only — BR-DOC-08)
        Long branchId = principal != null ? principal.branchId() : null;
        Long userId   = principal != null ? principal.userId() : null;

        GeneratedDocument gd = new GeneratedDocument(
                sourceCompanyId, branchId, docNumber, type,
                result.sourceType(), req.sourceUid(), req.sourceParams(),
                branding != null ? branding.getId() : null,
                userId != null ? userId : 0L);
        gd.setContentMeta(hash, result.bytes().length);
        generatedDocs.save(gd);

        // 8. Publish DOCUMENT.GENERATED in same TX (D-7 / BR-DOC-10)
        outboxPublisher.publish(
                DomainEventType.DOCUMENT_GENERATED,
                DomainEventType.AGG_GENERATED_DOCUMENT,
                gd.getId(), gd.getUid(),
                sourceCompanyId, branchId,
                new DocumentGeneratedPayload(
                        gd.getUid(), type, result.sourceType(), req.sourceUid(),
                        sourceCompanyId, branchId, userId, gd.getGeneratedAt()));

        // 9. Audit (FR-DOC-14)
        audit.record(AuditEvent.of(DOCUMENT_RENDER, "generated_documents",
                gd.getId(), gd.getUid())
                .detail(Map.of("documentType", type.name(), "docNumber", docNumber,
                        "sourceUid", req.sourceUid() != null ? req.sourceUid() : "")));

        return GeneratedDocumentDto.from(gd);
    }

    // -------------------------------------------------------------------------
    // download — re-renders from live source; NO new log row (D-4)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public byte[] download(String generatedDocumentUid) {
        RequestContext.Principal principal = RequestContext.get();

        GeneratedDocument gd = generatedDocs.findByUid(generatedDocumentUid)
                .orElseThrow(() -> new NotFoundException("Generated document not found."));

        scopeGuard.assertCanActIn(principal, gd.getCompanyId());

        DocumentBranding branding = brandings.findByCompanyId(gd.getCompanyId()).orElse(null);

        String title = templates
                .findByCompanyIdAndDocumentType(gd.getCompanyId(), gd.getDocumentType())
                .map(DocumentTemplate::getTitle)
                .orElse(gd.getDocumentType().name());

        RenderDocumentRequest req = new RenderDocumentRequest(
                gd.getDocumentType(), gd.getSourceUid(), gd.getSourceParams());

        RenderResult result = renderBytes(gd.getDocumentType(), req, branding, title);

        String newHash = sha256(result.bytes());
        if (gd.getContentHash() != null && !gd.getContentHash().equals(newHash)) {
            log.warn("Document {} content hash diverged: recorded={} current={}",
                    generatedDocumentUid, gd.getContentHash(), newHash);
        }

        return result.bytes();
    }

    // -------------------------------------------------------------------------
    // list + getByUid
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<GeneratedDocumentDto> list(Long companyId, DocumentType type, String sourceUid,
                                           Instant from, Instant to, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        // Build predicate in code to avoid SQLState 42P18: PostgreSQL cannot infer the column type
        // of a null bind value in (:p IS NULL OR col = :p) — same pattern as AuditReadService.
        Specification<GeneratedDocument> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("companyId"), companyId));
            if (type      != null) predicates.add(cb.equal(root.get("documentType"), type));
            if (sourceUid != null) predicates.add(cb.equal(root.get("sourceUid"), sourceUid));
            if (from      != null) predicates.add(cb.greaterThanOrEqualTo(root.get("generatedAt"), from));
            if (to        != null) predicates.add(cb.lessThanOrEqualTo(root.get("generatedAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return generatedDocs.findAll(spec, pageable)
                .map(GeneratedDocumentDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public GeneratedDocumentDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        GeneratedDocument gd = generatedDocs.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Generated document not found."));
        scopeGuard.assertCanActIn(principal, gd.getCompanyId());
        return GeneratedDocumentDto.from(gd);
    }

    // -------------------------------------------------------------------------
    // Internal render dispatch
    // -------------------------------------------------------------------------

    private record RenderResult(byte[] bytes, String sourceType) {}

    private RenderResult renderBytes(DocumentType type, RenderDocumentRequest req,
                                      DocumentBranding branding, String title) {
        return switch (type) {
            case INVOICE -> {
                assertSourceUidPresent(req);
                SalesInvoiceDto inv = salesInvoiceService.getByUid(req.sourceUid());
                assertRenderableInvoice(inv);
                List<SalesInvoiceLineDto> lines =
                        salesInvoiceService.listLines(req.sourceUid());
                DocumentRenderModel model = modelBuilder.buildInvoice(inv, lines, branding, title);
                yield new RenderResult(pdfRenderer.render(model), "SALES_INVOICE");
            }
            case PURCHASE_ORDER -> {
                assertSourceUidPresent(req);
                PurchaseOrderDto po = purchaseOrderService.getByUid(req.sourceUid());
                assertRenderablePurchaseOrder(po);
                List<PurchaseOrderLineDto> lines =
                        purchaseOrderService.listLines(req.sourceUid());
                DocumentRenderModel model = modelBuilder.buildPurchaseOrder(po, lines, branding, title);
                yield new RenderResult(pdfRenderer.render(model), "PURCHASE_ORDER");
            }
            case GOODS_RECEIPT -> {
                assertSourceUidPresent(req);
                GoodsReceiptDto gr = goodsReceiptService.getByUid(req.sourceUid());
                assertRenderableGoodsReceipt(gr);
                DocumentRenderModel model = modelBuilder.buildGoodsReceipt(gr, gr.lines(), branding, title);
                yield new RenderResult(pdfRenderer.render(model), "GOODS_RECEIPT");
            }
            case DELIVERY_NOTE -> {
                assertSourceUidPresent(req);
                DeliveryDto del = deliveryService.getByUid(req.sourceUid());
                // v1: deliveries are created CONFIRMED — always renderable (ADR-0021 D-2)
                DocumentRenderModel model = modelBuilder.buildDeliveryNote(del, del.lines(), branding, title);
                yield new RenderResult(pdfRenderer.render(model), "DELIVERY");
            }
            case CREDIT_NOTE -> {
                assertSourceUidPresent(req);
                ArCreditNoteDto cn = arCreditNoteService.getByUid(req.sourceUid());
                DocumentRenderModel model = modelBuilder.buildCreditNote(cn, branding, title);
                yield new RenderResult(pdfRenderer.render(model), "AR_CREDIT_NOTE");
            }
            case AR_STATEMENT -> {
                if (req.sourceParams() == null) {
                    throw new IllegalArgumentException(
                            "AR_STATEMENT requires sourceParams {customerUid, asAt}");
                }
                Map<String, String> params = parseParams(req.sourceParams());
                String customerUid = params.get("customerUid");
                String asAtStr     = params.get("asAt");
                if (customerUid == null || asAtStr == null) {
                    throw new IllegalArgumentException("sourceParams must contain customerUid and asAt");
                }
                Long custCompanyId = scopeGuard.companyIdOf("customer", customerUid)
                        .orElseThrow(() -> new NotFoundException("Customer not found."));
                ArStatementDto stmt = arAgeingQuery.statementByCustomerUid(
                        custCompanyId, customerUid, LocalDate.parse(asAtStr));
                StatementRenderModel stmtModel = modelBuilder.buildArStatement(stmt, branding, title);
                yield new RenderResult(statementPdfRenderer.render(stmtModel), "AR_STATEMENT");
            }
            default -> throw new IllegalArgumentException("Document type not renderable in v1: " + type);
        };
    }

    /**
     * Resolves the source company id via ScopeGuard.companyIdOf (ADR-0023 D-9).
     * ScopeGuard is the cross-cutting spine already wired to all source-module repos.
     */
    private Long resolveSourceCompanyId(DocumentType type, String sourceUid, String sourceParams) {
        // CREDIT_NOTE: ArCreditNoteRepository is not in ScopeGuard yet (ADR-0023 D-9 adds the case
        // to ScopeGuard — see the shared-file append). For now resolve via the service DTO.
        if (type == DocumentType.CREDIT_NOTE) {
            return arCreditNoteService.getByUid(sourceUid).companyId();
        }

        if (type == DocumentType.AR_STATEMENT) {
            String customerUid = parseParams(sourceParams).get("customerUid");
            return scopeGuard.companyIdOf("customer", customerUid)
                    .orElseThrow(() -> new NotFoundException("Customer not found."));
        }

        String targetType = switch (type) {
            case INVOICE        -> "invoice";
            case PURCHASE_ORDER -> "purchaseorder";
            case GOODS_RECEIPT  -> "goodsreceipt";
            case DELIVERY_NOTE  -> "delivery";
            default -> throw new IllegalArgumentException("Not renderable in v1: " + type);
        };

        return scopeGuard.companyIdOf(targetType, sourceUid)
                .orElseThrow(() -> NotFoundException.of("Source", sourceUid));
    }

    // -------------------------------------------------------------------------
    // Renderable-state guards (D-6 / BR-DOC-05)
    // -------------------------------------------------------------------------

    private void assertRenderableInvoice(SalesInvoiceDto inv) {
        if (inv.status() == null) return;
        String s = inv.status().name();
        if (!"FINALISED".equals(s) && !"VOID".equals(s)) {
            throw new IllegalStateException(
                    "Invoice is not in a renderable state: " + s
                    + ". Only FINALISED or VOID invoices can be rendered.");
        }
    }

    private void assertRenderablePurchaseOrder(PurchaseOrderDto po) {
        if (po.status() == null) return;
        if ("DRAFT".equals(po.status().name())) {
            throw new IllegalStateException("Purchase order is DRAFT — place the order first.");
        }
    }

    private void assertRenderableGoodsReceipt(GoodsReceiptDto gr) {
        if (gr.status() == null) return;
        if ("DRAFT".equals(gr.status().name())) {
            throw new IllegalStateException("Goods receipt is DRAFT — receive it first.");
        }
    }

    // -------------------------------------------------------------------------

    private void assertSourceUidPresent(RenderDocumentRequest req) {
        if (req.sourceUid() == null || req.sourceUid().isBlank()) {
            throw new IllegalArgumentException("sourceUid is required for " + req.documentType());
        }
    }

    private Map<String, String> parseParams(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid sourceParams JSON.");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
