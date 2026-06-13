/**
 * Stock batch/lot models — mirrors backend StockBatchDto exactly.
 * All Long id fields are typed `string`. BigDecimal qtyOnHand is typed `string`.
 */

export interface StockBatchDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  locationId: string;
  productId: string;
  lotNumber: string;
  manufactureDate: string | null;
  expiryDate: string | null;
  qtyOnHand: string;
  expired: boolean;
}
