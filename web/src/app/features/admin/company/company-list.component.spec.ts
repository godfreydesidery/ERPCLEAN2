import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyListComponent } from './company-list.component';

describe('CompanyListComponent — inline rename', () => {
  let fixture: ComponentFixture<CompanyListComponent>;
  let httpMock: HttpTestingController;
  let session: SessionStore;
  const apiBase = environment.apiBaseUrl;
  const orgUrl = `${apiBase}/organisations/current`;
  const accessibleUrl = `${apiBase}/companies/accessible`;

  const company: Company = {
    id: '1',
    uid: 'C1',
    organisationId: '1',
    code: 'C1',
    name: 'QA Company',
    legalName: 'QA Company Ltd',
    taxId: 'TIN-123',
    timeZone: 'Africa/Dar_es_Salaam',
    status: 'ACTIVE',
  };

  function setup(perms: string[]): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(perms);
    fixture = TestBed.createComponent(CompanyListComponent);
    httpMock = TestBed.inject(HttpTestingController);
    httpMock.expectOne(orgUrl).flush({ uid: 'ORG1', name: 'Acme' });
    httpMock.expectOne((r) => r.url === accessibleUrl).flush([company]);
    fixture.detectChanges();
  }

  afterEach(() => {
    httpMock.verify();
    session.clear();
  });

  it('renames a company, echoing legalName/taxId/timeZone so they are not nulled, and updates the row', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;
    expect(c.canManage()).toBe(true);

    c.startEdit(company);
    expect(c.editingUid()).toBe('C1');
    expect(c.editName()).toBe('QA Company');

    c.editName.set('Acme Tanzania Ltd');
    c.saveEdit(company);

    const req = httpMock.expectOne(`${apiBase}/companies/uid/C1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      name: 'Acme Tanzania Ltd',
      legalName: 'QA Company Ltd',
      taxId: 'TIN-123',
      timeZone: 'Africa/Dar_es_Salaam',
    });
    req.flush({ ...company, name: 'Acme Tanzania Ltd' });

    expect(c.editingUid()).toBeNull();
    expect(c.companies()[0].name).toBe('Acme Tanzania Ltd');
  });

  it('hides the rename control when the user lacks COMPANY.MANAGE', () => {
    setup([]);
    expect(fixture.componentInstance.canManage()).toBe(false);
    const editButtons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ).filter((b) => ((b as HTMLButtonElement).getAttribute('aria-label') ?? '').startsWith('Edit '));
    expect(editButtons.length).toBe(0);
  });

  it('does not call the API when the name is unchanged', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;
    c.startEdit(company);
    c.saveEdit(company); // editName still equals current name
    expect(c.editingUid()).toBeNull();
    // afterEach httpMock.verify() asserts no PUT was issued.
  });
});
