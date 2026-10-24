import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CancelRestaurantOrderRequest,
  CreatePrepStationRequest,
  CreateRestaurantTableRequest,
  KitchenTicketDto,
  ModifierGroupDto,
  PrepStationCategoryDto,
  PrepStationDto,
  ReplaceCategoryRoutesRequest,
  RestaurantOrderDto,
  RestaurantOrderStatus,
  RestaurantTableDto,
  RestaurantVoidResultDto,
  SaveModifierGroupRequest,
  UpdatePrepStationRequest,
  UpdateRestaurantTableRequest,
} from './models/restaurant.model';

/**
 * Restaurant API client (ADR-0065). Every list is per branch: pass the branch uid, or omit it to
 * use the caller's active branch (the X-Branch-Uid header). Order taking, sending and payment
 * happen on the till (OrbixPOS); the web side sets the restaurant up and watches live orders.
 */
@Injectable({ providedIn: 'root' })
export class RestaurantService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/restaurant`;

  // ── Tables ────────────────────────────────────────────────────────────────

  listTables(branchUid?: string): Observable<RestaurantTableDto[]> {
    return this.http.get<RestaurantTableDto[]>(`${this.base}/tables`, { params: branch(branchUid) });
  }

  createTable(request: CreateRestaurantTableRequest): Observable<RestaurantTableDto> {
    return this.http.post<RestaurantTableDto>(`${this.base}/tables`, request);
  }

  updateTable(uid: string, request: UpdateRestaurantTableRequest): Observable<RestaurantTableDto> {
    return this.http.put<RestaurantTableDto>(`${this.base}/tables/uid/${uid}`, request);
  }

  // ── Prep stations ─────────────────────────────────────────────────────────

  listStations(branchUid?: string): Observable<PrepStationDto[]> {
    return this.http.get<PrepStationDto[]>(`${this.base}/prep-stations`, { params: branch(branchUid) });
  }

  createStation(request: CreatePrepStationRequest): Observable<PrepStationDto> {
    return this.http.post<PrepStationDto>(`${this.base}/prep-stations`, request);
  }

  updateStation(uid: string, request: UpdatePrepStationRequest): Observable<PrepStationDto> {
    return this.http.put<PrepStationDto>(`${this.base}/prep-stations/uid/${uid}`, request);
  }

  // ── Category routing ──────────────────────────────────────────────────────

  listCategoryRoutes(branchUid?: string): Observable<PrepStationCategoryDto[]> {
    return this.http.get<PrepStationCategoryDto[]>(`${this.base}/category-routes`, {
      params: branch(branchUid),
    });
  }

  replaceCategoryRoutes(request: ReplaceCategoryRoutesRequest): Observable<PrepStationCategoryDto[]> {
    return this.http.put<PrepStationCategoryDto[]>(`${this.base}/category-routes`, request);
  }

  /** The product categories in use in the company — the rows of the routing screen. */
  listProductCategories(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/product-categories`);
  }

  // ── Choices & add-ons (ADR-0066 D-4) ─────────────────────────────────────

  listModifierGroups(): Observable<ModifierGroupDto[]> {
    return this.http.get<ModifierGroupDto[]>(`${this.base}/modifier-groups`);
  }

  createModifierGroup(request: SaveModifierGroupRequest): Observable<ModifierGroupDto> {
    return this.http.post<ModifierGroupDto>(`${this.base}/modifier-groups`, request);
  }

  updateModifierGroup(uid: string, request: SaveModifierGroupRequest): Observable<ModifierGroupDto> {
    return this.http.put<ModifierGroupDto>(`${this.base}/modifier-groups/uid/${uid}`, request);
  }

  // ── Kitchen display (ADR-0066 D-6) ────────────────────────────────────────

  kitchenTickets(stationUid: string): Observable<KitchenTicketDto[]> {
    return this.http.get<KitchenTicketDto[]>(`${this.base}/kitchen`, {
      params: new HttpParams().set('stationUid', stationUid),
    });
  }

  kitchenReady(lineUids: string[]): Observable<void> {
    return this.http.post<void>(`${this.base}/kitchen/ready`, { lineUids });
  }

  kitchenRecall(lineUids: string[]): Observable<void> {
    return this.http.post<void>(`${this.base}/kitchen/recall`, { lineUids });
  }

  // ── Orders ────────────────────────────────────────────────────────────────

  /** Live orders (OPEN, BILLED) unless other statuses are named. */
  listOrders(branchUid?: string, statuses: RestaurantOrderStatus[] = []): Observable<RestaurantOrderDto[]> {
    let params = branch(branchUid);
    for (const s of statuses) params = params.append('status', s);
    return this.http.get<RestaurantOrderDto[]>(`${this.base}/orders`, { params });
  }

  cancelOrder(uid: string, request: CancelRestaurantOrderRequest): Observable<RestaurantVoidResultDto> {
    return this.http.post<RestaurantVoidResultDto>(`${this.base}/orders/uid/${uid}/cancel`, request);
  }
}

function branch(branchUid?: string): HttpParams {
  return branchUid ? new HttpParams().set('branchUid', branchUid) : new HttpParams();
}
