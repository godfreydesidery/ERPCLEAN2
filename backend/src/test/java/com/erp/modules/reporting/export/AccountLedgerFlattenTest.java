package com.erp.modules.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.AccountLedgerRowDto;
import com.erp.modules.reporting.domain.dto.StatementHeaderDto;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The account-ledger export is one bounded page from the first line. These check that the printed
 * document always adds up: opening + the listed lines (+ one "not listed" line when the period has
 * more lines than fit) = the closing balance.
 */
class AccountLedgerFlattenTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 1);

    @Test
    void everyLineFits_noNotListedLine() {
        StatementRenderModel m = new StatementModelFlattener().flatten(ledger(2));

        assertThat(m.rows()).extracting(Row::label)
                .noneMatch(l -> l.contains("not listed"));
        assertThat(m.rows()).hasSize(4); // opening, 2 lines, closing
    }

    @Test
    void moreLinesThanTheExportHolds_carriesTheRestAsOneLine_soTheDocumentAddsUp() {
        // 5 lines of +100 in the period, only the first 2 exported: opening 1000, closing 1500.
        StatementRenderModel m = new StatementModelFlattener().flatten(ledger(5));

        Row notListed = m.rows().get(3);
        assertThat(notListed.label()).startsWith("3 further lines not listed");
        assertThat(notListed.current()).isEqualByComparingTo("300");

        BigDecimal sum = m.rows().subList(0, m.rows().size() - 1).stream()
                .map(Row::current).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(m.rows().get(m.rows().size() - 1).current());
    }

    /** {@code total} lines of +100 in the period, of which the first two are on the page. */
    private static AccountLedgerDto ledger(int total) {
        BigDecimal opening = new BigDecimal("1000");
        List<AccountLedgerRowDto> page = List.of(
                new AccountLedgerRowDto(D, "MANUAL", "J1", "E1", null,
                        new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1100")),
                new AccountLedgerRowDto(D, "MANUAL", "J2", "E2", null,
                        new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1200")));
        BigDecimal closing = opening.add(new BigDecimal(100L * total));
        StatementHeaderDto header = new StatementHeaderDto(1L, "Co", "TZS", "Sep", null,
                D, D.plusDays(29), null, Instant.now(), null, null);
        return new AccountLedgerDto(header, 9L, "ACC", "1000", "Cash", opening,
                page, closing, 0, 2, total);
    }
}
