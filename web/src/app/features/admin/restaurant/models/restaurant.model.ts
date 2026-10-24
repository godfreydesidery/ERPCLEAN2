/**
 * Restaurant orders and setup (ADR-0065). Ids are strings on the wire (Long → JSON string);
 * money amounts are numbers (BigDecimal → JSON number).
 */

export type MasterStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';
export type RestaurantTableState = 'FREE' | 'OCCUPIED' | 'BILLED';
export type RestaurantOrderType = 'DINE_IN' | 'TAKEAWAY';
export type RestaurantOrderStatus = 'OPEN' | 'BILLED' | 'PAID' | 'CANCELLED';
export type RestaurantOrderLineStatus = 'PENDING' | 'SENT' | 'VOIDED';
export type PrepSlipKind = 'FIRE' | 'VOID';

export interface RestaurantTableDto {
  id: string;
  uid: string;
  branchId: string;
  name: string;
  seats: number;
  area: string | null;
  sortOrder: number;
  status: MasterStatus;
  state: RestaurantTableState;
  liveOrderCount: number;
}

export interface CreateRestaurantTableRequest {
  branchUid?: string | null;
  name: string;
  seats?: number | null;
  area?: string | null;
  sortOrder?: number | null;
}

export interface UpdateRestaurantTableRequest {
  name: string;
  seats?: number | null;
  area?: string | null;
  sortOrder?: number | null;
  status?: MasterStatus | null;
}

export interface PrepStationDto {
  id: string;
  uid: string;
  branchId: string;
  name: string;
  printerHost: string | null;
  printerPort: number;
  defaultStation: boolean;
  status: MasterStatus;
}

export interface CreatePrepStationRequest {
  branchUid?: string | null;
  name: string;
  printerHost?: string | null;
  printerPort?: number | null;
  defaultStation: boolean;
}

export interface UpdatePrepStationRequest {
  name: string;
  printerHost?: string | null;
  printerPort?: number | null;
  defaultStation: boolean;
  status?: MasterStatus | null;
}

export interface PrepStationCategoryDto {
  uid: string;
  category: string;
  prepStationUid: string | null;
  prepStationName: string | null;
}

export interface CategoryRoute {
  category: string;
  prepStationUid: string;
}

export interface ReplaceCategoryRoutesRequest {
  branchUid?: string | null;
  routes: CategoryRoute[];
}

export interface RestaurantOrderLineDto {
  id: string;
  uid: string;
  lineNo: number;
  productId: string;
  productUid: string | null;
  productCode: string | null;
  productName: string;
  unitId: string;
  unitName: string | null;
  quantity: number;
  note: string | null;
  status: RestaurantOrderLineStatus;
  fireRound: number | null;
  prepStationUid: string | null;
  prepStationName: string | null;
  sentAt: string | null;
  voidedAt: string | null;
  voidReason: string | null;
}

export interface RestaurantOrderDto {
  id: string;
  uid: string;
  branchId: string;
  orderNumber: string;
  orderType: RestaurantOrderType;
  status: RestaurantOrderStatus;
  tableUid: string | null;
  tableName: string | null;
  guestCount: number | null;
  customerId: string | null;
  openedBy: string;
  openedByName: string;
  openedAt: string;
  billedAt: string | null;
  billedTotalAmount: number | null;
  billPrintCount: number;
  paidAt: string | null;
  salesInvoiceUid: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  notes: string | null;
  pendingLineCount: number;
  lines: RestaurantOrderLineDto[];
}

export interface PrepSlipLine {
  productName: string;
  unitName: string | null;
  quantity: number;
  note: string | null;
  voidReason: string | null;
}

export interface PrepSlipDto {
  kind: PrepSlipKind;
  stationUid: string;
  stationName: string;
  printerHost: string | null;
  printerPort: number;
  orderNumber: string;
  orderType: RestaurantOrderType;
  tableName: string | null;
  guestCount: number | null;
  round: number;
  reprint: boolean;
  byName: string;
  at: string;
  lines: PrepSlipLine[];
}

export interface RestaurantVoidResultDto {
  order: RestaurantOrderDto;
  slips: PrepSlipDto[];
}

export interface CancelRestaurantOrderRequest {
  reason?: string | null;
  authorisedByUid?: string | null;
}
