/**
 * Documents module wire models — mirror the backend DTOs exactly.
 * All Long / BigDecimal fields are strings on the wire.
 * Phase-B frontend contract: docs/frontend/contracts/documents.md
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type DocumentType =
  | 'INVOICE'
  | 'AR_STATEMENT'
  | 'PURCHASE_ORDER'
  | 'GOODS_RECEIPT'
  | 'DELIVERY_NOTE'
  | 'CREDIT_NOTE'
  | 'PAYSLIP'
  | 'QUOTATION'
  | 'DEBIT_NOTE';

export type RendererKey = 'TRANSACTIONAL_PDF' | 'STATEMENT_PDF';

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── Generated Document (render log) ──────────────────────────────────────────

/** GeneratedDocumentDto — the render-log record returned by /api/v1/documents */
export interface GeneratedDocumentDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  documentNumber: string;
  documentType: DocumentType;
  sourceType: string;
  sourceUid: string;
  sourceParams: string;
  brandingId: string;
  contentHash: string;
  byteSize: string;
  mimeType: string;
  generatedBy: string;
  generatedAt: string; // Instant — ISO-8601
  downloadUrl: string;
}

// ── Render request ────────────────────────────────────────────────────────────

/** RenderDocumentRequest — POST /api/v1/documents/render */
export interface RenderDocumentRequest {
  documentType: DocumentType;
  /** Required for INVOICE, PURCHASE_ORDER, GOODS_RECEIPT, DELIVERY_NOTE, CREDIT_NOTE */
  sourceUid?: string;
  /** Required for AR_STATEMENT — JSON string e.g. {"customerUid":..,"fromDate":..,"toDate":..} */
  sourceParams?: string;
}

// ── Document Template ─────────────────────────────────────────────────────────

/** DocumentTemplateDto — returned by /api/v1/documents/templates */
export interface DocumentTemplateDto {
  id: string;
  uid: string;
  companyId: string;
  documentType: DocumentType;
  rendererKey: RendererKey;
  brandingId: string;
  title: string;
  status: MasterStatus;
  version: string;
}

/** UpdateDocumentTemplateRequest — PUT /api/v1/documents/templates/{uid} */
export interface UpdateDocumentTemplateRequest {
  title: string;
  status: MasterStatus;
  brandingId: string;
  /** Optimistic-lock version — must be round-tripped from the loaded entity */
  version: string;
}

// ── Document Branding ─────────────────────────────────────────────────────────

/** DocumentBrandingDto — returned by /api/v1/documents/branding */
export interface DocumentBrandingDto {
  id: string;
  uid: string;
  companyId: string;
  displayName: string;
  legalName: string;
  taxId: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  region: string;
  country: string;
  postalCode: string;
  contactPhone: string;
  contactEmail: string;
  website: string;
  logoRef: string;
  footerTerms: string;
  bankDetails: string;
  updatedAt: string; // Instant — ISO-8601
  version: string;
}

/** UpdateDocumentBrandingRequest — PUT /api/v1/documents/branding */
export interface UpdateDocumentBrandingRequest {
  displayName: string;
  legalName: string;
  taxId: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  region: string;
  country: string;
  postalCode: string;
  contactPhone: string;
  contactEmail: string;
  website: string;
  logoRef: string;
  footerTerms: string;
  bankDetails: string;
  /** Optimistic-lock version — must be round-tripped from the loaded entity */
  version: string;
}
