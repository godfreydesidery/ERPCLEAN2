/**
 * BulkImportService spec.
 *
 * Covers:
 *  1. listEntities() GETs /bulk/entities.
 *  2. downloadTemplate() GETs /bulk/{key}/template with responseType 'blob'.
 *  3. validate()/commit() POST multipart FormData with field name `file` — no explicit
 *     Content-Type header (the browser sets the multipart boundary).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { BulkImportService } from './bulk-import.service';

describe('BulkImportService', () => {
  let service: BulkImportService;
  let httpMock: HttpTestingController;
  const base = `${environment.apiBaseUrl}/bulk`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BulkImportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('listEntities GETs /bulk/entities', () => {
    service.listEntities().subscribe();
    const req = httpMock.expectOne(`${base}/entities`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('downloadTemplate GETs the template with responseType blob', () => {
    service.downloadTemplate('PRODUCT').subscribe();
    const req = httpMock.expectOne(`${base}/PRODUCT/template`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['xlsx-bytes']));
  });

  it('validate() POSTs a FormData body with field name "file" and no explicit Content-Type', () => {
    const file = new File(['dummy'], 'products.xlsx');
    service.validate('PRODUCT', file).subscribe();
    const req = httpMock.expectOne(`${base}/PRODUCT/validate`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBe(file);
    expect(req.request.headers.has('Content-Type')).toBe(false);
    req.flush({ entityKey: 'PRODUCT', mode: 'VALIDATE', total: 0, created: 0, updated: 0, errors: 0, rows: [] });
  });

  it('commit() POSTs a FormData body with field name "file"', () => {
    const file = new File(['dummy'], 'products.xlsx');
    service.commit('PRODUCT', file).subscribe();
    const req = httpMock.expectOne(`${base}/PRODUCT/commit`);
    expect(req.request.method).toBe('POST');
    expect((req.request.body as FormData).get('file')).toBe(file);
    req.flush({ entityKey: 'PRODUCT', mode: 'COMMIT', total: 0, created: 0, updated: 0, errors: 0, rows: [] });
  });
});
