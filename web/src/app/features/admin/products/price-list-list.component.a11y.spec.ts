/**
 * Accessibility gate — PriceListListComponent.
 *
 * The screen gained a Default column, a per-row "Set default" action and two guidance banners; the
 * table and the buttons are the markup axe has not already seen on this page.
 *
 * The CI-starvation guard lives inside assertA11y() (it renders but skips the scan when
 * process.env.CI is set), so specs only supply the wider per-test timeout.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { assertA11y } from '../../../../testing/a11y.helper';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { PriceListDto } from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PriceListListComponent } from './price-list-list.component';
import { ProductService } from './product.service';

const ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const CO = { uid: 'CO1', id: '10', name: 'Main Co' };

function priceList(over: Partial<PriceListDto> = {}): PriceListDto {
  return {
    id: '1', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail',
    priceIncludesVat: true, isDefault: false, status: 'ACTIVE', version: null,
    createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
    ...over,
  };
}

const ROWS = [
  priceList({ isDefault: true }),
  priceList({ id: '2', uid: 'PL2', code: 'WHOLE', name: 'Wholesale' }),
  priceList({ id: '3', uid: 'PL3', code: 'OLD', name: 'Old list', status: 'ARCHIVED' }),
];

function makeBed(rows: PriceListDto[] = ROWS) {
  TestBed.configureTestingModule({
    imports: [PriceListListComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      {
        provide: ProductService,
        useValue: {
          listPriceLists: vi.fn(() => of(rows)),
          createPriceList: vi.fn(() => of(rows[0])),
          updatePriceList: vi.fn(() => of(rows[0])),
          setDefaultPriceList: vi.fn(() => of(rows[0])),
          archivePriceList: vi.fn(() => of(rows[0])),
          restorePriceList: vi.fn(() => of(rows[0])),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([CO])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
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

describe('PriceListListComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with the Default column and the row actions', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with the "no default set" banner', async () => {
    // Two unflagged ACTIVE lists: with only one, the server falls back to it and no banner shows.
    makeBed([priceList(), priceList({ id: '2', uid: 'PL2', code: 'WHOLE', name: 'Wholesale' })]);
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.hasNoDefault()).toBe(true);
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with the create form open, including the default checkbox', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleCreateForm();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
