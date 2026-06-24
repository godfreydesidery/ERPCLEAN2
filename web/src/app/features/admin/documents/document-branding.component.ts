import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SlicePipe } from '@angular/common';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DocumentsService } from './documents.service';
import {
  DocumentBrandingDto,
  UpdateDocumentBrandingRequest,
} from './models/documents.model';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Document branding singleton screen — GET + PUT for the active company's branding profile.
 * Route: /admin/document-branding
 * Perm: DOCUMENT.BRANDING.MANAGE
 * Branding is a per-company singleton (no create/delete). PUT is an upsert; version is optimistic-lock.
 */
@Component({
  selector: 'app-document-branding',
  imports: [FormsModule, SlicePipe],
  templateUrl: './document-branding.component.html',
  styleUrl: './document-branding.component.scss',
})
export class DocumentBrandingComponent {
  private readonly docService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<DocumentBrandingDto | null>(null);
  readonly state = signal<LoadState>('loading');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Form fields ──────────────────────────────────────────────────────────────
  readonly fDisplayName = signal('');
  readonly fLegalName = signal('');
  readonly fTaxId = signal('');
  readonly fAddressLine1 = signal('');
  readonly fAddressLine2 = signal('');
  readonly fCity = signal('');
  readonly fRegion = signal('');
  readonly fCountry = signal('');
  readonly fPostalCode = signal('');
  readonly fContactPhone = signal('');
  readonly fContactEmail = signal('');
  readonly fWebsite = signal('');
  readonly fLogoRef = signal('');
  /** Inline logo as a base64 data URI ('' = none). Set from an upload, downscaled to ≤256 px. */
  readonly fLogoDataUri = signal('');
  readonly logoError = signal<string | null>(null);
  readonly fFooterTerms = signal('');
  readonly fBankDetails = signal('');

  /** Max stored logo data-URI length — mirrors the V75 DB CHECK / request @Size (~70 KB). */
  private static readonly LOGO_MAX_CHARS = 72000;

  readonly canManage = computed(() => this.session.hasPermission('DOCUMENT.BRANDING.MANAGE'));

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.docService.getBranding().subscribe({
      next: (branding) => {
        this.entity.set(branding);
        this.patchForm(branding);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  private patchForm(b: DocumentBrandingDto): void {
    this.fDisplayName.set(b.displayName ?? '');
    this.fLegalName.set(b.legalName ?? '');
    this.fTaxId.set(b.taxId ?? '');
    this.fAddressLine1.set(b.addressLine1 ?? '');
    this.fAddressLine2.set(b.addressLine2 ?? '');
    this.fCity.set(b.city ?? '');
    this.fRegion.set(b.region ?? '');
    this.fCountry.set(b.country ?? '');
    this.fPostalCode.set(b.postalCode ?? '');
    this.fContactPhone.set(b.contactPhone ?? '');
    this.fContactEmail.set(b.contactEmail ?? '');
    this.fWebsite.set(b.website ?? '');
    this.fLogoRef.set(b.logoRef ?? '');
    this.fLogoDataUri.set(b.logoDataUri ?? '');
    this.fFooterTerms.set(b.footerTerms ?? '');
    this.fBankDetails.set(b.bankDetails ?? '');
  }

  /**
   * Handle a logo file pick: validate PNG/JPEG, downscale to ≤256 px on a canvas, and store the
   * result as a base64 data URI (kept under the ~70 KB cap, re-encoding as JPEG if needed).
   */
  onLogoSelected(event: Event): void {
    this.logoError.set(null);
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // allow re-selecting the same file later
    if (!file) return;
    if (!/^image\/(png|jpeg)$/.test(file.type)) {
      this.logoError.set('Use a PNG or JPEG image.');
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => this.logoError.set('Could not read the file.');
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => this.logoError.set('Could not read the image.');
      img.onload = () => this.storeDownscaledLogo(img, file.type);
      img.src = reader.result as string;
    };
    reader.readAsDataURL(file);
  }

  private storeDownscaledLogo(img: HTMLImageElement, sourceType: string): void {
    const max = 256;
    const scale = Math.min(1, max / Math.max(img.width, img.height));
    const w = Math.max(1, Math.round(img.width * scale));
    const h = Math.max(1, Math.round(img.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) { this.logoError.set('Could not process the image.'); return; }
    ctx.drawImage(img, 0, 0, w, h);
    // Prefer the source format; fall back to (lower-quality) JPEG if the PNG is over the cap.
    let dataUri = canvas.toDataURL(sourceType === 'image/png' ? 'image/png' : 'image/jpeg', 0.85);
    if (dataUri.length > DocumentBrandingComponent.LOGO_MAX_CHARS) {
      dataUri = canvas.toDataURL('image/jpeg', 0.7);
    }
    if (dataUri.length > DocumentBrandingComponent.LOGO_MAX_CHARS) {
      this.logoError.set('Logo is too detailed to store — try a simpler image.');
      return;
    }
    this.fLogoDataUri.set(dataUri);
  }

  clearLogo(): void {
    this.fLogoDataUri.set('');
    this.logoError.set(null);
  }

  save(): void {
    const displayName = this.fDisplayName().trim();
    if (!displayName) {
      this.saveError.set('Display name is required.');
      return;
    }

    const current = this.entity();
    if (!current) {
      this.saveError.set('Branding not loaded yet.');
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateDocumentBrandingRequest = {
      displayName,
      legalName: this.fLegalName().trim(),
      taxId: this.fTaxId().trim(),
      addressLine1: this.fAddressLine1().trim(),
      addressLine2: this.fAddressLine2().trim(),
      city: this.fCity().trim(),
      region: this.fRegion().trim(),
      country: this.fCountry().trim(),
      postalCode: this.fPostalCode().trim(),
      contactPhone: this.fContactPhone().trim(),
      contactEmail: this.fContactEmail().trim(),
      website: this.fWebsite().trim(),
      logoRef: this.fLogoRef().trim(),
      logoDataUri: this.fLogoDataUri(),
      footerTerms: this.fFooterTerms().trim(),
      bankDetails: this.fBankDetails().trim(),
      version: current.version,
    };

    this.docService.updateBranding(request).subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.patchForm(updated);
        this.saving.set(false);
        this.alerts.success('Branding saved', updated.displayName);
      },
      error: (err: unknown) => {
        this.saveError.set(this.messageFrom(err, 'Could not save branding.'));
        this.saving.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
