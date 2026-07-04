import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddBarcodeRequest,
  AddComponentRequest,
  AssignProductBranchRequest,
  CreateBulkPackRequest,
  CreatePriceListRequest,
  CreateProductRequest,
  CreateUnitOfMeasureRequest,
  PriceListDto,
  ProductBarcodeDto,
  ProductBranchDto,
  ProductBulkPackDto,
  ProductComponentDto,
  ProductModel,
  ProductPriceDto,
  SetProductPriceRequest,
  UnitOfMeasureDto,
  UpdatePriceListRequest,
  UpdateProductRequest,
  UpdateUnitOfMeasureRequest,
} from '../models/product.model';

export interface ProductPage {
  rows: ProductModel[];
  meta: PageMeta;
}

/**
 * Product API client.
 * list() uses SKIP_UNWRAP to read both data and PageMeta.
 * All other methods use the auto-unwrap path (interceptor strips the envelope).
 * Base: /api/v1/products; price-lists: /api/v1/price-lists.
 */
@Injectable({ providedIn: 'root' })
export class ProductService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/products`;
  private readonly priceListBase = `${environment.apiBaseUrl}/price-lists`;

  // ── Product CRUD ──────────────────────────────────────────────────────────

  list(companyId: string, q?: string, page = 0, size = 20): Observable<ProductPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ProductModel[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<ProductModel> {
    return this.http.get<ProductModel>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateProductRequest): Observable<ProductModel> {
    return this.http.post<ProductModel>(this.base, request);
  }

  update(uid: string, request: UpdateProductRequest): Observable<ProductModel> {
    return this.http.put<ProductModel>(`${this.base}/uid/${uid}`, request);
  }

  archive(uid: string): Observable<ProductModel> {
    return this.http.put<ProductModel>(`${this.base}/uid/${uid}/archive`, {});
  }

  restore(uid: string): Observable<ProductModel> {
    return this.http.put<ProductModel>(`${this.base}/uid/${uid}/restore`, {});
  }

  barcodeLookup(companyId: string, barcode: string): Observable<ProductModel> {
    const params = new HttpParams().set('companyId', companyId).set('barcode', barcode);
    return this.http.get<ProductModel>(`${this.base}/barcode-lookup`, { params });
  }

  // ── Barcodes ──────────────────────────────────────────────────────────────

  listBarcodes(uid: string): Observable<ProductBarcodeDto[]> {
    return this.http.get<ProductBarcodeDto[]>(`${this.base}/uid/${uid}/barcodes`);
  }

  addBarcode(uid: string, request: AddBarcodeRequest): Observable<ProductBarcodeDto> {
    return this.http.post<ProductBarcodeDto>(`${this.base}/uid/${uid}/barcodes`, request);
  }

  removeBarcode(uid: string, barcodeUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/barcodes/${barcodeUid}`);
  }

  // ── Bulk Packs ────────────────────────────────────────────────────────────

  listBulkPacks(uid: string): Observable<ProductBulkPackDto[]> {
    return this.http.get<ProductBulkPackDto[]>(`${this.base}/uid/${uid}/bulk-packs`);
  }

  addBulkPack(uid: string, request: CreateBulkPackRequest): Observable<ProductBulkPackDto> {
    return this.http.post<ProductBulkPackDto>(`${this.base}/uid/${uid}/bulk-packs`, request);
  }

  removeBulkPack(uid: string, bulkPackUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/bulk-packs/${bulkPackUid}`);
  }

  // ── Prices ────────────────────────────────────────────────────────────────

  listPrices(uid: string): Observable<ProductPriceDto[]> {
    return this.http.get<ProductPriceDto[]>(`${this.base}/uid/${uid}/prices`);
  }

  /** unitUid absent/undefined ⇒ base-unit price row; set ⇒ per-unit (pack) price row (ADR-0048). */
  setPrice(uid: string, request: SetProductPriceRequest): Observable<ProductPriceDto> {
    return this.http.post<ProductPriceDto>(`${this.base}/uid/${uid}/prices`, request);
  }

  /** unitUid absent ⇒ base row (back-compatible); set ⇒ the per-unit (pack) price row (ADR-0048). */
  removePrice(uid: string, priceListUid: string, unitUid?: string): Observable<void> {
    const params = unitUid ? new HttpParams().set('unitUid', unitUid) : undefined;
    return this.http.delete<void>(`${this.base}/uid/${uid}/prices/${priceListUid}`, { params });
  }

  // ── Components / Recipe ───────────────────────────────────────────────────

  listComponents(uid: string): Observable<ProductComponentDto[]> {
    return this.http.get<ProductComponentDto[]>(`${this.base}/uid/${uid}/components`);
  }

  addComponent(uid: string, request: AddComponentRequest): Observable<ProductComponentDto> {
    return this.http.post<ProductComponentDto>(`${this.base}/uid/${uid}/components`, request);
  }

  removeComponent(uid: string, componentUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/components/${componentUid}`);
  }

  // ── Branch Associations ───────────────────────────────────────────────────

  listBranches(uid: string): Observable<ProductBranchDto[]> {
    return this.http.get<ProductBranchDto[]>(`${this.base}/uid/${uid}/branches`);
  }

  assignBranch(uid: string, request: AssignProductBranchRequest): Observable<ProductBranchDto> {
    return this.http.post<ProductBranchDto>(`${this.base}/uid/${uid}/branches`, request);
  }

  removeBranch(uid: string, branchUid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/branches/${branchUid}`);
  }

  // ── Product-scoped units ──────────────────────────────────────────────────

  /**
   * Returns the valid transaction units for a specific product:
   * base unit first, then each active bulk-pack unit (de-duplicated).
   * Calls GET /api/v1/products/uid/{uid}/units — gated by PRODUCT.VIEW.
   */
  listProductUnits(uid: string): Observable<UnitOfMeasureDto[]> {
    return this.http.get<UnitOfMeasureDto[]>(`${this.base}/uid/${uid}/units`);
  }

  // ── Units of Measure ─────────────────────────────────────────────────────

  private readonly unitBase = `${environment.apiBaseUrl}/units`;

  listUnits(companyId: string, q?: string, page = 0, size = 200): Observable<{ rows: UnitOfMeasureDto[]; meta: PageMeta }> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (q?.trim()) params = params.set('q', q.trim());
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<UnitOfMeasureDto[]>>(this.unitBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getUnitByUid(uid: string): Observable<UnitOfMeasureDto> {
    return this.http.get<UnitOfMeasureDto>(`${this.unitBase}/uid/${uid}`);
  }

  createUnit(request: CreateUnitOfMeasureRequest): Observable<UnitOfMeasureDto> {
    return this.http.post<UnitOfMeasureDto>(this.unitBase, request);
  }

  updateUnit(uid: string, request: UpdateUnitOfMeasureRequest): Observable<UnitOfMeasureDto> {
    return this.http.put<UnitOfMeasureDto>(`${this.unitBase}/uid/${uid}`, request);
  }

  archiveUnit(uid: string): Observable<UnitOfMeasureDto> {
    return this.http.put<UnitOfMeasureDto>(`${this.unitBase}/uid/${uid}/archive`, {});
  }

  restoreUnit(uid: string): Observable<UnitOfMeasureDto> {
    return this.http.put<UnitOfMeasureDto>(`${this.unitBase}/uid/${uid}/restore`, {});
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  listPriceLists(companyId: string): Observable<PriceListDto[]> {
    return this.http.get<PriceListDto[]>(this.priceListBase, { params: { companyId } });
  }

  getPriceListByUid(uid: string): Observable<PriceListDto> {
    return this.http.get<PriceListDto>(`${this.priceListBase}/uid/${uid}`);
  }

  createPriceList(request: CreatePriceListRequest): Observable<PriceListDto> {
    return this.http.post<PriceListDto>(this.priceListBase, request);
  }

  updatePriceList(uid: string, request: UpdatePriceListRequest): Observable<PriceListDto> {
    return this.http.put<PriceListDto>(`${this.priceListBase}/uid/${uid}`, request);
  }

  archivePriceList(uid: string): Observable<PriceListDto> {
    return this.http.put<PriceListDto>(`${this.priceListBase}/uid/${uid}/archive`, {});
  }

  restorePriceList(uid: string): Observable<PriceListDto> {
    return this.http.put<PriceListDto>(`${this.priceListBase}/uid/${uid}/restore`, {});
  }
}
