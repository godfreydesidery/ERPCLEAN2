/**
 * Accessibility gate — FixedAssetListComponent.
 *
 * Covers the fixed-asset register list screen (filter + table + empty state).
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
import { FixedAssetsService } from './fixed-assets.service';
import type { FixedAssetPage } from './fixed-assets.service';
import { FixedAssetListComponent } from './fixed-asset-list.component';
import { assertA11y } from '../../../../testing/a11y.helper';

const emptyPage = (): FixedAssetPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeBed(listReturnValue: FixedAssetPage) {
  TestBed.configureTestingModule({
    imports: [FixedAssetListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: FixedAssetsService,
        useValue: { list: vi.fn(() => of(listReturnValue)) },
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
          hasPermission: vi.fn(() => false),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('FixedAssetListComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the empty-state', async () => {
    makeBed(emptyPage());
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(FixedAssetListComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers(); // restore before running axe
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
