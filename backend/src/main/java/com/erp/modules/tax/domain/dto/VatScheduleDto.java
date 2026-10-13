package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.VatReturnStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * RPT-14 / PAR-06: a VAT return's per-document schedule — the sales (output) or purchases (input)
 * listing behind its totals. Rows are in base currency; credit notes, debit notes and voids are
 * negative rows, so {@code totalVat} equals the return's output (sales) or input (purchases) VAT.
 */
public record VatScheduleDto(
        String returnNumber,
        Long companyId,
        LocalDate periodStart,
        LocalDate periodEnd,
        VatReturnStatus status,
        List<Row> rows,
        BigDecimal totalNet,
        BigDecimal totalVat
) {
    /**
     * One document on the schedule.
     *
     * @param documentType  INVOICE, VOID, CREDIT_NOTE (sales); BILL, DEBIT_NOTE, CASH_EXPENSE
     *                      (purchases — a cash/bank expense entry that claimed input VAT; its memo
     *                      is the party column)
     * @param documentNumber our invoice / note number; for a bill, the SUPPLIER's tax invoice number
     * @param ourReference  for a bill, our bill number; otherwise null
     * @param fiscalNumber  the EFD / fiscal receipt number when one was issued; otherwise null
     */
    public record Row(
            LocalDate date,
            String documentType,
            String documentNumber,
            String ourReference,
            String partyName,
            String tin,
            String vrn,
            BigDecimal net,
            BigDecimal vat,
            String fiscalNumber
    ) {}
}
