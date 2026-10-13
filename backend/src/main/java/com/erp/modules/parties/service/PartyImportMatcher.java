package com.erp.modules.parties.service;

import com.erp.platform.bulk.ImportContext;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * PRD-12: matches a blank-code customer/supplier import row to a party that already exists, so
 * re-uploading a sheet (e.g. after fixing the rows that failed the first time) updates instead of
 * creating a second copy of every party. Match order: TIN (strong identifier), then the display
 * name compared case- and space-insensitively. Phone is deliberately not used on its own — shops
 * in one family often share a number, and a wrong match would rename somebody else's account.
 *
 * <p>Also refuses a blank-code row whose name or TIN already appeared earlier in the same file,
 * so the duplicate shows at Validate instead of becoming two parties at Commit.
 */
final class PartyImportMatcher {

    private PartyImportMatcher() {
    }

    static String normaliseName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Claims the row's name/TIN within the file; throws a friendly message on a repeat. */
    static void claimInFile(ImportContext ctx, String label, String name, String tin) {
        String n = normaliseName(name);
        if (!n.isEmpty() && !ctx.claimSet(label + ".name").add(n)) {
            throw new IllegalArgumentException("'" + name.trim() + "' appears more than once in this file. "
                    + "Keep one row per " + label + ", or put the code in the Code column.");
        }
        String t = tin == null ? "" : tin.trim();
        if (!t.isEmpty() && !ctx.claimSet(label + ".tin").add(t.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("TIN " + t + " appears more than once in this file. "
                    + "Keep one row per " + label + ".");
        }
    }

    /**
     * The existing party this blank-code row describes, or null when none does.
     *
     * @throws IllegalArgumentException when the TIN or name matches more than one party
     */
    static <T> T match(String label, String name, String tin,
                       Function<String, List<T>> byTin, Function<String, List<T>> byName) {
        String t = tin == null ? "" : tin.trim();
        if (!t.isEmpty()) {
            List<T> hits = byTin.apply(t);
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.size() > 1) {
                throw new IllegalArgumentException("More than one " + label + " has TIN " + t
                        + ". Put the " + label + "'s code in the Code column to choose which one to update.");
            }
        }
        String n = name == null ? "" : name.trim();
        if (!n.isEmpty()) {
            List<T> hits = byName.apply(n);
            if (hits.size() == 1) {
                return hits.get(0);
            }
            if (hits.size() > 1) {
                throw new IllegalArgumentException("More than one " + label + " is named '" + n
                        + "'. Put the " + label + "'s code in the Code column to choose which one to update.");
            }
        }
        return null;
    }
}
