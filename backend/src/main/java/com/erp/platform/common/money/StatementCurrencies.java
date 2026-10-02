package com.erp.platform.common.money;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Which currencies a party statement (customer or supplier) prints when the caller named none, and
 * in what order. Shared by the AR and AP statement reads so the two documents behave the same.
 *
 * <p>Rule: one section per currency the party actually has movements in — amounts in different
 * currencies are never added together. Order: the party's own default currency first (when set),
 * then the company's base currency, then any other alphabetically. A party with no movements at
 * all gets a single section in its own default currency, or the base currency when it has none.
 */
public final class StatementCurrencies {

    private StatementCurrencies() {}

    /**
     * @param present         currencies the party has movements in (any order, may repeat or be empty)
     * @param partyDefault    the party record's default transaction currency; null when unset
     * @param companyBase     the company's base currency
     * @return the section currencies, upper-case, never empty
     */
    public static List<String> order(List<String> present, String partyDefault, String companyBase) {
        String own  = norm(partyDefault);
        String base = norm(companyBase);
        TreeSet<String> rest = new TreeSet<>();
        if (present != null) {
            for (String c : present) {
                String n = norm(c);
                if (n != null) {
                    rest.add(n);
                }
            }
        }
        List<String> out = new ArrayList<>(rest.size() + 1);
        if (rest.isEmpty()) {
            out.add(own != null ? own : (base != null ? base : "TZS"));
            return out;
        }
        if (own != null && rest.remove(own)) {
            out.add(own);
        }
        if (base != null && rest.remove(base)) {
            out.add(base);
        }
        out.addAll(rest);
        return out;
    }

    private static String norm(String c) {
        if (c == null || c.isBlank()) {
            return null;
        }
        return c.trim().toUpperCase(Locale.ROOT);
    }
}
