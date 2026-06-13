/**
 * Stock serial number models — mirrors backend StockSerialDto exactly.
 * All Long id fields are typed `string`.
 */

export type SerialStatus = 'IN_STOCK' | 'ISSUED' | 'RETURNED';

export interface StockSerialDto {
  id: string;
  uid: string;
  companyId: string;
  branchId: string;
  locationId: string;
  productId: string;
  serialNumber: string;
  serialStatus: SerialStatus;
  receivedDocumentUid: string | null;
  issuedDocumentUid: string | null;
  createdAt: string | null;
}
