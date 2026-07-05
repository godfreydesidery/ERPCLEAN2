/**
 * Accessibility gate — MassPriceChangeComponent.
 *
 * Covers: the change-rule form (idle, no result yet), and the preview-results panel with a
 * samples table rendered — each scanned with axe-core against WCAG 2.1 AA.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { MassPriceChangeService } from './mass-price-change.service';
import { MassPriceChangeComponent } from './mass-price-change.component';
import { assertA11y } from '../../../../testing/a11y.helper';
import type { PriceListDto } from '../models/product.model';

const PRICE_LIST: PriceListDto = {
  id: '30', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail Price',
  isDefault: true, status: 'ACTIVE', version: null,
  createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [MassPriceChangeComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: MassPriceChangeService,
        useValue: {
          apply: vi.fn(() =>
            of({
              priceListUid: 'PL1', priceListCode: 'RETAIL', priceListName: 'Retail Price',
              totalRows: 10, affected: 8, dryRun: true,
              samples: [{ productCode: 'SKU-1', oldAmount: '100.00', newAmount: '110.00', currency: 'TZS' }],
            }),
          ),
        },
      },
      {
        provide: ProductService,
        useValue: { listPriceLists: vi.fn(() => of([PRICE_LIST])) },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal<string | null>(null),
        },
      },
    ],
  });
}

describe('MassPriceChangeComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('the change-rule form has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(MassPriceChangeComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('the preview-results panel with samples has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(MassPriceChangeComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
