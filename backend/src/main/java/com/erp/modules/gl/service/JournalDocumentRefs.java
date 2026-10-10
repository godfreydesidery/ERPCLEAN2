package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.util.regex.Pattern;

/**
 * Reads the human-readable document number an automatic posting put in its description (ACC-19).
 * The posters write "Goods receipt GRN-0007", "COGS — sale INV-0453", "Landed cost LC-12": the
 * number is the last word. Manual journals are skipped (their text is the user's own), and a ULID
 * is never returned — it is the machine ref, not a number a person recognises.
 */
final class JournalDocumentRefs {

    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    /** A document number: letters/digits with separators, containing at least one digit. */
    private static final Pattern DOC_NUMBER =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]*$");

    private JournalDocumentRefs() {
    }

    static String fromDescription(String description, JournalSourceType sourceType) {
        if (description == null || description.isBlank()
                || sourceType == null || sourceType == JournalSourceType.MANUAL) {
            return null;
        }
        String[] tokens = description.trim().split("\\s+");
        String last = tokens[tokens.length - 1].replaceAll("[.,;:)]+$", "");
        if (last.length() < 3 || ULID.matcher(last).matches()
                || !DOC_NUMBER.matcher(last).matches() || !last.matches(".*\\d.*")) {
            return null;
        }
        return last;
    }
}
