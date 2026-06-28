package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards against exception messages that leak internal identifiers (uids / ids) or internal
 * reference codes (BR-/ADR-/FR-/OQ-/D-&lt;n&gt;) to user-facing error strings — a violation of
 * the error-message hygiene rule (MEMORY: error-message-hygiene).
 *
 * <p><b>Patterns detected (single-line and multi-line):</b>
 * <ul>
 *   <li>A {@code new XxxException} constructor whose string argument (possibly spanning continuation
 *       lines up to the closing {@code ;}) is concatenated with an identifier ending in
 *       {@code uid}, {@code Uid}, or {@code Id}.</li>
 *   <li>A {@code new XxxException} constructor whose string argument contains a hard-coded
 *       internal reference code matching {@code BR-}, {@code ADR-}, {@code FR-}, {@code OQ-}, or
 *       {@code D-&lt;digit&gt;}.</li>
 * </ul>
 *
 * <p>The scanner collects <em>throw blocks</em>: starting from any line that opens a
 * {@code throw new XxxException(} call (and is not a comment or log line), it accumulates
 * subsequent lines until the statement's closing {@code ;} is reached, then tests the joined
 * block. This catches multi-line constructions like:
 * <pre>
 *   throw new ConflictException(
 *           "Text: " + req.documentUid()   // &lt;-- continuation caught by block scan
 *           + " status " + status);
 * </pre>
 *
 * <p><b>Exclusions (applied per line before block accumulation begins):</b>
 * <ul>
 *   <li>Lines whose trimmed content starts with {@code //} — single-line comments.</li>
 *   <li>Lines that contain a {@code log.} call — log messages may include ids for diagnostics.</li>
 * </ul>
 * Continuation lines inside an already-opened block are included unconditionally (the comment on a
 * continuation line is not a statement boundary, and the block scanner stops at {@code ;}).
 *
 * <p><b>Scope:</b> {@code src/main/java/com/erp} — all production Java sources.
 *
 * <p>This test MUST pass with zero offenders. Fix by replacing the concatenation with a generic
 * user message and keeping the identifier / code in a {@code log.*} call.
 */
class MessageHygieneTest {

    /**
     * Detects the opening of a throw statement that may be offending.
     * Matches any line containing {@code throw new XxxException(} that is not a comment/log line.
     */
    private static final Pattern THROW_OPEN = Pattern.compile(
            "throw\\s+new\\s+\\w+Exception\\(");

    /**
     * Detects uid/id concatenation inside the accumulated throw-block text.
     *
     * <p>Matches {@code + <expr>uid} or {@code + <expr>Uid} or {@code + <expr>Id} or
     * {@code + <expr>getId()} / {@code getUid()} style chains anywhere in the block.
     * The leading {@code +} anchors it to a concatenation, not a legitimate identifier name
     * that happens to end in "uid" appearing inside a string literal.
     */
    private static final Pattern UID_CONCAT = Pattern.compile(
            "\\+\\s*[\\w.]*(?:[Uu]id|Id)(\\(\\))?");

    /**
     * Detects hard-coded internal reference codes in the accumulated throw-block text.
     * Matches codes like BR-SALES-06, ADR-0042, FR-PURCH-09, OQ-APR-06, D-3 inside
     * a string literal (between double-quotes).
     */
    private static final Pattern INTERNAL_CODE = Pattern.compile(
            "\"[^\"]*(?:BR-|ADR-|FR-|OQ-|D-\\d)[^\"]*\"");

    private static final Path SOURCE_ROOT =
            Paths.get("src/main/java/com/erp").toAbsolutePath();

    @Test
    void exceptionMessagesMustNotConcatenateUidOrIdIdentifiers() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> checkFile(file, violations));
        }

        assertThat(violations)
                .as(
                        "Exception messages must not concatenate uid/id identifiers or embed internal "
                                + "reference codes into user-facing strings (error-message-hygiene rule).\n"
                                + "Replace the message with plain language and log the detail at "
                                + "DEBUG/WARN level instead.\n"
                                + "Offending locations:\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Implementation
    // -------------------------------------------------------------------------

    private void checkFile(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.strip();

            // Skip single-line comments and log statements at the statement opener.
            if (trimmed.startsWith("//") || line.contains("log.")) {
                i++;
                continue;
            }

            if (THROW_OPEN.matcher(line).find()) {
                // Accumulate this throw statement until the closing ';'.
                int startLine = i + 1; // 1-based for reporting
                StringBuilder block = new StringBuilder(line);
                // If the statement does not end on the opening line, keep adding lines.
                while (!statementClosed(block.toString()) && i + 1 < lines.size()) {
                    i++;
                    block.append('\n').append(lines.get(i));
                }
                String blockText = block.toString();
                // Strip content inside /* block comments */ before testing
                // (simple single-pass: remove // tail-comments from each block line).
                String cleaned = stripLineComments(blockText);

                if (UID_CONCAT.matcher(cleaned).find()) {
                    violations.add(relativePath(file) + ":" + startLine
                            + " [uid/id concat in multi-line throw] → "
                            + trimmed);
                }
                if (INTERNAL_CODE.matcher(cleaned).find()) {
                    violations.add(relativePath(file) + ":" + startLine
                            + " [internal code in throw message] → "
                            + trimmed);
                }
            }

            i++;
        }
    }

    /**
     * Returns true when the accumulated block text contains a {@code ;} outside of string literals,
     * indicating the throw statement has been fully collected.
     *
     * <p>This is a best-effort scan: it tracks whether each character is inside a double-quoted
     * string (accounting for backslash escapes) and treats the first {@code ;} found outside a
     * string as the statement terminator.
     */
    private static boolean statementClosed(String block) {
        boolean inString = false;
        for (int j = 0; j < block.length(); j++) {
            char c = block.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++; // skip escaped char
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == ';') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strips {@code //}-style tail comments from each line of a multi-line block string,
     * but only when the {@code //} appears outside a string literal.
     * This prevents false negatives where a comment on a continuation line mentions a uid.
     */
    private static String stripLineComments(String block) {
        StringBuilder out = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            boolean inString = false;
            int cutAt = line.length();
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (inString) {
                    if (c == '\\') {
                        j++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == '/' && j + 1 < line.length() && line.charAt(j + 1) == '/') {
                        cutAt = j;
                        break;
                    }
                }
            }
            out.append(line, 0, cutAt).append('\n');
        }
        return out.toString();
    }

    private String relativePath(Path file) {
        return SOURCE_ROOT.relativize(file.toAbsolutePath()).toString();
    }
}
