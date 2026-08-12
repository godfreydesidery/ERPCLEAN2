/**
 * Accessibility gate — StockReportComponent.
 *
 * Covers the toolbar + company header + table + total-value row.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { StockReportService } from './stock-report.service';
import { StockReportComponent } from './stock-report.component';
import { StockReportDto } from './models/stock-report.model';
import { assertA11y } from '../../../../testing/a11y.helper';

const MOCK_REPORT: StockReportDto = {
  company: {
    name: 'SAM Electronix Ltd', legalName: 'SAM Electronix Company Limited',
    addressLine1: 'Plot 12', addressLine2: null, city: 'Dar es Salaam', region: null,
    country: 'Tanzania', contactPhone: '+255700000000', contactEmail: 'info@sam.co.tz',
    taxId: 'TIN-999', vrn: 'VRN-888',
  },
  branchUid: 'BR-UID-KILI',
  branchName: 'Kilimanjaro',
  branchLabel: 'Kilimanjaro',
  currency: 'TZS',
  rows: [
    { productCode: 'P001', productName: 'Widget', quantityOnHand: 10, buyingPrice: 1000, sellingPrice: 1500, value: 10000 },
  ],
  totalValue: 10000,
  generatedAt: '2026-07-19T12:00:00Z',
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [StockReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockReportService,
        useValue: { report: vi.fn(() => of(MOCK_REPORT)), export: vi.fn(() => of(new Blob())) },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('StockReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(StockReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
