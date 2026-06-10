package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.reporting.domain.enums.StatementSection;
import org.springframework.stereotype.Component;

/**
 * Pure-function classifier: (AccountType, accountCode) → StatementSection (ADR-0018 D-4).
 * No DB, no state. The auto-derive rule — no template table (BR-REP-07).
 *
 * <p>Exact bands (OQ-REP-01 default, finance-reviewable — code change, no migration):
 * <pre>
 * ASSET    1000–1499  → CURRENT_ASSETS
 * ASSET    1500–1999  → NON_CURRENT_ASSETS
 * ASSET    other      → CURRENT_ASSETS (type default)
 * LIABILITY 2000–2499 → CURRENT_LIABILITIES
 * LIABILITY 2500–2999 → NON_CURRENT_LIABILITIES
 * LIABILITY other     → CURRENT_LIABILITIES (type default)
 * EQUITY   any 3xxx   → EQUITY
 * INCOME   any 4xxx   → REVENUE
 * EXPENSE  5100–5199  → COST_OF_SALES  (5100 COGS + 5150 Purchases)
 * EXPENSE  other      → OPERATING_EXPENSES
 * </pre>
 */
@Component
public class StatementClassifier {

    public StatementSection classify(AccountType type, String accountCode) {
        int code = parseLeadingCode(accountCode);
        return switch (type) {
            case ASSET      -> classifyAsset(code);
            case LIABILITY  -> classifyLiability(code);
            case EQUITY     -> StatementSection.EQUITY;
            case INCOME     -> StatementSection.REVENUE;
            case EXPENSE    -> classifyExpense(code);
        };
    }

    /** CF section for a working-capital or investing/financing account. */
    public StatementSection classifyForCashFlow(AccountType type, String accountCode) {
        int code = parseLeadingCode(accountCode);
        return switch (type) {
            case ASSET -> {
                if (code >= 1000 && code <= 1499) yield StatementSection.OPERATING; // current operating assets
                if (code >= 1500 && code <= 1999) yield StatementSection.INVESTING;
                yield StatementSection.OPERATING; // default
            }
            case LIABILITY -> {
                if (code >= 2000 && code <= 2499) yield StatementSection.OPERATING; // current liabilities
                if (code >= 2500 && code <= 2999) yield StatementSection.FINANCING;
                yield StatementSection.OPERATING;
            }
            case EQUITY  -> StatementSection.FINANCING;
            case INCOME, EXPENSE -> StatementSection.OPERATING; // net income feeds OPERATING
        };
    }

    // -------------------------------------------------------------------------

    private StatementSection classifyAsset(int code) {
        if (code >= 1000 && code <= 1499) return StatementSection.CURRENT_ASSETS;
        if (code >= 1500 && code <= 1999) return StatementSection.NON_CURRENT_ASSETS;
        return StatementSection.CURRENT_ASSETS; // out-of-band default (BR-REP-07)
    }

    private StatementSection classifyLiability(int code) {
        if (code >= 2000 && code <= 2499) return StatementSection.CURRENT_LIABILITIES;
        if (code >= 2500 && code <= 2999) return StatementSection.NON_CURRENT_LIABILITIES;
        return StatementSection.CURRENT_LIABILITIES; // out-of-band default
    }

    private StatementSection classifyExpense(int code) {
        // 5100–5199: COGS + Purchases = COST_OF_SALES (D-4 note, 5150 is Purchases)
        if (code >= 5100 && code <= 5199) return StatementSection.COST_OF_SALES;
        return StatementSection.OPERATING_EXPENSES; // 5200–5999 and all other EXPENSE
    }

    /**
     * Parses the leading numeric run of an account code (e.g. "5150" → 5150, "5200-A" → 5200).
     * Returns -1 for blank / non-numeric codes (falls through to the type default).
     */
    static int parseLeadingCode(String accountCode) {
        if (accountCode == null || accountCode.isBlank()) return -1;
        StringBuilder sb = new StringBuilder();
        for (char c : accountCode.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        if (sb.isEmpty()) return -1;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
