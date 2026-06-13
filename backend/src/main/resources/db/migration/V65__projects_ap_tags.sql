-- V65 — Projects: AP / supplier bill line tags (ADR-0033 D-3/D-10).
-- ALTER supplier_bill_lines — add project_id + project_task_id for drill-down + AP leg threading.
-- Additive only. V1–V64 are FROZEN.

-- ============================================================================
-- (1) ALTER supplier_bill_lines — project tag columns
-- ============================================================================
ALTER TABLE supplier_bill_lines
    ADD COLUMN project_id      BIGINT,
    ADD COLUMN project_task_id BIGINT;

ALTER TABLE supplier_bill_lines
    ADD CONSTRAINT fk_supplier_bill_line_project      FOREIGN KEY (project_id)
        REFERENCES projects (id),
    ADD CONSTRAINT fk_supplier_bill_line_project_task FOREIGN KEY (project_task_id)
        REFERENCES project_tasks (id);

-- Partial index for drill-down (project-tagged bill lines)
CREATE INDEX ix_supplier_bill_lines_project
    ON supplier_bill_lines (company_id, project_id)
    WHERE project_id IS NOT NULL;
