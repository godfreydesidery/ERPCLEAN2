-- V28 — Cost-centre document-default columns on source documents (ADR-0025 D-6 / D-9):
-- ALTER sales_invoices — add cost_centre_value_id + department_value_id (header-level default);
-- ALTER supplier_bills — add cost_centre_value_id + department_value_id (header-level default);
-- ALTER stock_movements — add cost_centre_value_id + department_value_id (adjustment carrier).
-- All columns nullable — an untagged document posts untagged lines, unchanged (NFR-CC-01).
-- Additive only. V1–V27 are FROZEN.

-- ============================================================================
-- (1) ALTER sales_invoices — header-level dimension default (ADR-0025 D-6, FR-CC-10)
-- The SalesPostingHandler re-reads these when building the revenue/COGS LineDrafts.
-- ============================================================================
ALTER TABLE sales_invoices
    ADD COLUMN cost_centre_value_id BIGINT,
    ADD COLUMN department_value_id  BIGINT;

ALTER TABLE sales_invoices
    ADD CONSTRAINT fk_sales_invoice_cost_centre
        FOREIGN KEY (cost_centre_value_id) REFERENCES dimension_values (id),
    ADD CONSTRAINT fk_sales_invoice_department
        FOREIGN KEY (department_value_id)  REFERENCES dimension_values (id);

-- ============================================================================
-- (2) ALTER supplier_bills — header-level dimension default (ADR-0025 D-6, FR-CC-11)
-- BillMatchServiceImpl.postMatchedBillToGl reads these when posting DR Purchases / CR AP.
-- ============================================================================
ALTER TABLE supplier_bills
    ADD COLUMN cost_centre_value_id BIGINT,
    ADD COLUMN department_value_id  BIGINT;

ALTER TABLE supplier_bills
    ADD CONSTRAINT fk_supplier_bill_cost_centre
        FOREIGN KEY (cost_centre_value_id) REFERENCES dimension_values (id),
    ADD CONSTRAINT fk_supplier_bill_department
        FOREIGN KEY (department_value_id)  REFERENCES dimension_values (id);

-- ============================================================================
-- (3) ALTER stock_movements — dimension default carrier for adjustments (ADR-0025 D-6, FR-CC-12)
-- InventoryGlPoster.postAdjustmentDirect reads these when posting the adjustment expense leg.
-- Only the ADJUSTMENT movement type carries a dimension tag; receipt/issue movements are
-- tagged via their document (GR / invoice header) when those posters are wired. Nullable.
-- ============================================================================
ALTER TABLE stock_movements
    ADD COLUMN cost_centre_value_id BIGINT,
    ADD COLUMN department_value_id  BIGINT;

ALTER TABLE stock_movements
    ADD CONSTRAINT fk_stock_movement_cost_centre
        FOREIGN KEY (cost_centre_value_id) REFERENCES dimension_values (id),
    ADD CONSTRAINT fk_stock_movement_department
        FOREIGN KEY (department_value_id)  REFERENCES dimension_values (id);
