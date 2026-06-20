import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { BranchAdminComponent } from './branch-admin.component';

/**
 * The headline guarantee: opening the standalone Branches page resolves the org and then loads the
 * company picker from GET /companies/accessible (the auth-only, assignment-scoped endpoint) — NOT
 * the COMPANY.VIEW-gated GET /companies. That is what lets a BRANCH.VIEW/BRANCH.MANAGE-only user
 * reach branches. httpMock.verify() in afterEach fails the test if the unscoped endpoint is hit.
 */
describe('BranchAdminComponent', () => {
  let fixture: ComponentFixture<BranchAdminComponent>;
  let httpMock: HttpTestingController;
  const apiBase = environment.apiBaseUrl;
  const orgUrl = `${apiBase}/organisations/current`;
  const accessibleUrl = `${apiBase}/companies/accessible`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(BranchAdminComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function resolveOrg(): void {
    httpMock.expectOne(orgUrl).flush({ uid: 'ORG1', name: 'Acme Group' });
  }

  it('loads the company picker from /companies/accessible (no COMPANY.VIEW required)', () => {
    resolveOrg();
    const req = httpMock.expectOne(
      (r) => r.url === accessibleUrl && r.params.get('organisationUid') === 'ORG1',
    );
    expect(req.request.method).toBe('GET');
    req.flush([
      { uid: 'C1', code: 'C1', name: 'Acme TZ' },
      { uid: 'C2', code: 'C2', name: 'Acme KE' },
    ]);

    fixture.detectChanges();
    expect(fixture.componentInstance.companies().length).toBe(2);
    expect(fixture.componentInstance.companiesState()).toBe('idle');
    // Two companies → no auto-pick, so the embedded branch list is not rendered (no /branches call).
    expect(fixture.componentInstance.selectedCompanyUid()).toBe('');
    expect(fixture.nativeElement.querySelector('#branchCompany')).toBeTruthy();
  });

  it('auto-selects the company when the user can act in exactly one', () => {
    resolveOrg();
    httpMock
      .expectOne((r) => r.url === accessibleUrl)
      .flush([{ uid: 'C1', code: 'C1', name: 'Acme TZ' }]);

    // Intentionally no detectChanges: assert the selection signal without rendering the embedded
    // branch list (which would issue its own GET /branches and is out of scope for this spec).
    expect(fixture.componentInstance.selectedCompanyUid()).toBe('C1');
  });

  it('shows an empty state when the user is assigned to no company', () => {
    resolveOrg();
    httpMock.expectOne((r) => r.url === accessibleUrl).flush([]);

    fixture.detectChanges();
    expect(fixture.componentInstance.companies().length).toBe(0);
    expect(fixture.nativeElement.querySelector('.erp-empty')).toBeTruthy();
  });

  it('surfaces an error state if the organisation cannot be resolved', () => {
    httpMock.expectOne(orgUrl).error(new ProgressEvent('error'));

    fixture.detectChanges();
    expect(fixture.componentInstance.orgState()).toBe('error');
  });
});
