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
 * Guards against exception messages that leak internal identifiers (uids / ids) to user-facing
 * error strings — a violation of the error-message hygiene rule (MEMORY: error-message-hygiene).
 *
 * <p><b>Patterns detected:</b> any source line (not a line comment, not a log statement) that
 * contains a {@code new XxxException} constructor call whose string argument is concatenated with
 * an identifier whose name ends in {@code uid}, {@code Uid}, or {@code Id}, in either form:
 *
 * <pre>
 *   // bare variable form:
 *   new NotFoundException("some text " + companyUid)
 *   new BadRequestException("entity: " + companyId)
 *   // method-call form:
 *   new ConflictException("error: " + req.companyUid())
 *   new NotFoundException("not found: " + x.getId())
 * </pre>
 *
 * <p><b>Exclusions:</b>
 * <ul>
 *   <li>Lines starting with {@code //} (after trim) — single-line comments.</li>
 *   <li>Lines that contain a {@code log.} call — log messages may include ids for diagnostics.</li>
 * </ul>
 *
 * <p><b>Scope:</b> {@code src/main/java/com/erp} — all production Java sources.
 *
 * <p>This test MUST pass with zero offenders. If it fails, replace the concatenation with a
 * generic message (e.g. "Record not found") — keep the identifier in the log at DEBUG/WARN level.
 */
class MessageHygieneTest {

    /**
     * Matches {@code new XxxException} constructor calls that concatenate a string literal with an
     * identifier (bare variable or method-call chain) whose name ends in {@code uid}, {@code Uid},
     * or {@code Id} (case-sensitive suffix match).
     *
     * <p>Regex breakdown:
     * <ul>
     *   <li>{@code new \w+Exception\(} — exception instantiation (requires the {@code new} keyword
     *       so log call chains and orElseThrow lambdas are caught via the inner {@code new}).</li>
     *   <li>{@code "[^"]*"} — the string literal argument.</li>
     *   <li>{@code \s*\+\s*} — concatenation operator.</li>
     *   <li>{@code [\w.]*(?:[Uu]id|Id)} — bare variable or dotted method chain ending in a
     *       uid/Id suffix (e.g. {@code companyUid}, {@code req.companyUid}, {@code x.getId}).</li>
     *   <li>{@code (\(\))?} — optional call parens for the method-call form.</li>
     * </ul>
     *
     * <p>Examples matched:
     * <ul>
     *   <li>{@code new NotFoundException("not found: " + companyUid)}</li>
     *   <li>{@code new ConflictException("Company: " + req.companyUid())}</li>
     *   <li>{@code new NotFoundException("not found: " + x.getId())}</li>
     * </ul>
     */
    private static final Pattern OFFENDING_LINE = Pattern.compile(
            "new \\w+Exception\\(\"[^\"]*\"\\s*\\+\\s*[\\w.]*(?:[Uu]id|Id)(\\(\\))?");

    private static final Path SOURCE_ROOT =
            Paths.get("src/main/java/com/erp").toAbsolutePath();

    @Test
    void exceptionMessagesMustNotConcatenateUidOrIdIdentifiers() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> {
                        List<String> lines;
                        try {
                            lines = Files.readAllLines(file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            String trimmed = line.strip();
                            // Skip single-line comments
                            if (trimmed.startsWith("//")) {
                                continue;
                            }
                            // Skip log statements — they may include ids for diagnostics
                            if (line.contains("log.")) {
                                continue;
                            }
                            if (OFFENDING_LINE.matcher(line).find()) {
                                violations.add(
                                        SOURCE_ROOT.relativize(file.toAbsolutePath())
                                                + ":" + (i + 1)
                                                + " → " + trimmed);
                            }
                        }
                    });
        }

        assertThat(violations)
                .as(
                        "Exception messages must not concatenate uid/id identifiers into user-facing "
                                + "strings (error-message-hygiene rule).\n"
                                + "Replace the message with a generic string and log the id at "
                                + "DEBUG/WARN level instead.\n"
                                + "Offending lines:\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }
}
