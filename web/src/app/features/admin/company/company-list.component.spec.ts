import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyListComponent } from './company-list.component';

describe('CompanyListComponent — provision defaults', () => {
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
    vrn: null,
    contactPhone: null,
    contactEmail: null,
    addressLine1: null,
    addressLine2: null,
    city: null,
    region: null,
    country: null,
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
    vi.restoreAllMocks();
  });

  it('calls provisionDefaults with the company uid and shows a success alert on 200', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;
    const alerts = TestBed.inject(AlertService);
    vi.spyOn(alerts, 'success');
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    c.provisionDefaults(company);
    expect(c.provisioningUid()).toBe('C1');

    const req = httpMock.expectOne(`${apiBase}/companies/uid/C1/provision-defaults`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...company });

    expect(c.provisioningUid()).toBeNull();
    expect(alerts.success).toHaveBeenCalledWith('Default setup restored', company.name);
  });

  it('hides the Provision defaults button when the user lacks COMPANY.MANAGE', () => {
    setup([]);
    expect(fixture.componentInstance.canManage()).toBe(false);
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const provisionBtn = buttons.find((b) =>
      (b.getAttribute('aria-label') ?? '').startsWith('Provision defaults'),
    );
    expect(provisionBtn).toBeUndefined();
  });

  it('shows an error alert and clears spinner on failure', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;
    const alerts = TestBed.inject(AlertService);
    vi.spyOn(alerts, 'error');
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    c.provisionDefaults(company);
    httpMock
      .expectOne(`${apiBase}/companies/uid/C1/provision-defaults`)
      .flush({ errors: ['Server error'] }, { status: 500, statusText: 'Error' });

    expect(c.provisioningUid()).toBeNull();
    expect(alerts.error).toHaveBeenCalled();
  });

  it('does NOT call the API when the confirm dialog is cancelled', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;
    vi.spyOn(window, 'confirm').mockReturnValue(false);

    c.provisionDefaults(company);

    expect(c.provisioningUid()).toBeNull();
    httpMock.expectNone(`${apiBase}/companies/uid/C1/provision-defaults`);
  });
});

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
    vrn: null,
    contactPhone: null,
    contactEmail: null,
    addressLine1: null,
    addressLine2: null,
    city: null,
    region: null,
    country: null,
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
      vrn: undefined,
      contactPhone: undefined,
      contactEmail: undefined,
      addressLine1: undefined,
      addressLine2: undefined,
      city: undefined,
      region: undefined,
      country: undefined,
    });
    req.flush({ ...company, name: 'Acme Tanzania Ltd' });

    expect(c.editingUid()).toBeNull();
    expect(c.companies()[0].name).toBe('Acme Tanzania Ltd');
  });

  it('saves the extended VRN/address/contact fields (SAM Electronix go-live)', () => {
    setup(['COMPANY.MANAGE']);
    const c = fixture.componentInstance;

    c.startEdit(company);
    c.editVrn.set('VRN-777');
    c.editContactPhone.set('+255700000000');
    c.editContactEmail.set('info@qa.co.tz');
    c.editAddressLine1.set('Plot 12');
    c.editAddressLine2.set('Nyerere Road');
    c.editCity.set('Dar es Salaam');
    c.editRegion.set('Dar');
    c.editCountry.set('Tanzania');
    c.saveEdit(company);

    const req = httpMock.expectOne(`${apiBase}/companies/uid/C1`);
    expect(req.request.body).toEqual(
      expect.objectContaining({
        vrn: 'VRN-777',
        contactPhone: '+255700000000',
        contactEmail: 'info@qa.co.tz',
        addressLine1: 'Plot 12',
        addressLine2: 'Nyerere Road',
        city: 'Dar es Salaam',
        region: 'Dar',
        country: 'Tanzania',
      }),
    );
    req.flush({
      ...company,
      vrn: 'VRN-777',
      contactPhone: '+255700000000',
      contactEmail: 'info@qa.co.tz',
      addressLine1: 'Plot 12',
      addressLine2: 'Nyerere Road',
      city: 'Dar es Salaam',
      region: 'Dar',
      country: 'Tanzania',
    });

    expect(c.editingUid()).toBeNull();
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
