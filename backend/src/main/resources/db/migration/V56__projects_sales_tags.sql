-- V66 — Projects: Sales / O2C tags (ADR-0033 D-3/D-4/D-10).
-- ALTER sales_invoices, sales_invoice_lines, sales_orders, sales_order_lines,
--       deliveries, delivery_lines — add project_id (+ project_task_id where line-grained).
-- Propagation path: SO tag → delivery tag → invoice tag → GL revenue leg (D-4d).
-- Additive only. V1–V65 are FROZEN.

-- ============================================================================
-- (1) ALTER sales_invoices — header project tag (OQ-PROJ-07)
-- ============================================================================
ALTER TABLE sales_invoices
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE sales_invoices
    ADD CONSTRAINT fk_sales_invoice_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_sales_invoice_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

CREATE INDEX ix_sales_invoices_project
    ON sales_invoices (company_id, project_id)
    WHERE project_id IS NOT NULL;

-- ============================================================================
-- (2) ALTER sales_invoice_lines — optional per-line override (OQ-PROJ-07)
-- ============================================================================
ALTER TABLE sales_invoice_lines
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE sales_invoice_lines
    ADD CONSTRAINT fk_sales_invoice_line_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_sales_invoice_line_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

CREATE INDEX ix_sales_invoice_lines_project
    ON sales_invoice_lines (project_id)
    WHERE project_id IS NOT NULL;

-- ============================================================================
-- (3) ALTER sales_orders — propagation source (D-4d)
-- ============================================================================
ALTER TABLE sales_orders
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE sales_orders
    ADD CONSTRAINT fk_sales_order_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_sales_order_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

-- ============================================================================
-- (4) ALTER sales_order_lines — propagation source line (D-4d)
-- ============================================================================
ALTER TABLE sales_order_lines
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE sales_order_lines
    ADD CONSTRAINT fk_sales_order_line_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_sales_order_line_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

-- ============================================================================
-- (5) ALTER deliveries — propagation (D-4d)
-- ============================================================================
ALTER TABLE deliveries
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE deliveries
    ADD CONSTRAINT fk_delivery_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_delivery_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

-- ============================================================================
-- (6) ALTER delivery_lines — propagation + COGS-leg threading (D-4d/D-5)
-- ============================================================================
ALTER TABLE delivery_lines
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE delivery_lines
    ADD CONSTRAINT fk_delivery_line_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_delivery_line_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);
