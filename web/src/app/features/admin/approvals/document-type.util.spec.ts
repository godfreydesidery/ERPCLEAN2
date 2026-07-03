import { describe, it, expect } from 'vitest';
import { documentTypeLabel, documentTypeRoute } from './document-type.util';

describe('document-type.util', () => {
  describe('documentTypeLabel', () => {
    it('maps PURCHASE_ORDER to a friendly label', () => {
      expect(documentTypeLabel('PURCHASE_ORDER')).toBe('Purchase Order');
    });

    it('maps SALES_ORDER to a friendly label', () => {
      expect(documentTypeLabel('SALES_ORDER')).toBe('Sales Order');
    });

    it('maps GOODS_RECEIPT, RFQ and AP_BILL to friendly labels', () => {
      expect(documentTypeLabel('GOODS_RECEIPT')).toBe('Goods Receipt');
      expect(documentTypeLabel('RFQ')).toBe('Request for Quotation');
      expect(documentTypeLabel('AP_BILL')).toBe('Supplier Bill');
    });

    it('falls back to the raw code for an unmapped type', () => {
      expect(documentTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('documentTypeRoute', () => {
    it('resolves PURCHASE_ORDER to the purchase-order detail screen by uid', () => {
      expect(documentTypeRoute('PURCHASE_ORDER', 'PO-UID-1')).toEqual([
        '/admin/purchase-orders/uid',
        'PO-UID-1',
      ]);
    });

    it('resolves SALES_ORDER to the sales-order detail screen by uid', () => {
      expect(documentTypeRoute('SALES_ORDER', 'SO-UID-1')).toEqual([
        '/admin/sales-orders/uid',
        'SO-UID-1',
      ]);
    });

    it('resolves AP_BILL to the supplier-bill detail screen by uid', () => {
      expect(documentTypeRoute('AP_BILL', 'BILL-UID-1')).toEqual([
        '/admin/ap/supplier-bills/uid',
        'BILL-UID-1',
      ]);
    });

    it('returns null for a type with no known detail screen (e.g. AR_INVOICE — list-only route)', () => {
      expect(documentTypeRoute('AR_INVOICE', 'INV-UID-1')).toBeNull();
    });

    it('returns null for a wholly unmapped type — never falls back to an unrelated screen', () => {
      expect(documentTypeRoute('SOMETHING_NEW', 'UID-1')).toBeNull();
    });
  });
});
