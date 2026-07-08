-- V90 — Surface scale-step rounding on weighed sale lines (ADR-0044 D-1b follow-up).
--
-- A weighed line's quantity is normalised to the product's scale division (e.g. 0.813 kg → 0.815 kg
-- at a 0.005 kg step). Storing only the billed quantity hid that adjustment: a customer comparing a
-- scale ticket (0.813) to the receipt (0.815) had nothing to show the rounding was deliberate.
--
-- requested_quantity records what the caller actually entered, in the line's unit. It equals
-- quantity for non-weighed / non-rounded lines and differs only when scale-step rounding moved it,
-- so the receipt/line can display "entered 0.813 kg → billed 0.815 kg". Nullable + additive: existing
-- rows and non-weighed lines simply leave it null (no CHECK, no backfill), consistent with the
-- frozen-schema / durable-DB discipline.

ALTER TABLE sales_invoice_lines
    ADD COLUMN requested_quantity NUMERIC(19,6);
