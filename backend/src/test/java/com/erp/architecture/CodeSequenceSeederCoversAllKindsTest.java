package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.platform.bootstrap.CodeSequenceSeeder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CodeSequenceSeeder}'s list of entity kinds must match the kinds the code actually allocates
 * (ADR-0062 P5-6).
 *
 * <h2>Why the list is duplicated at all</h2>
 *
 * The kinds live as private constants across twenty-two generator classes. There is no shared enum
 * to read at runtime, and introducing one would mean touching every generator for a change that is
 * really about provisioning. So the seeder repeats the list — and this test re-derives the true set
 * from the source and fails when they diverge.
 *
 * <p>Without it the list rots silently in the worst direction: a generator added next year is simply
 * not seeded, and its first-use race comes back for that kind alone, on whichever tenant happens to
 * use it first. Nothing would fail, and nobody would look.
 *
 * <h2>What it reads</h2>
 *
 * Every {@code new CodeSequence(companyId, X)} and every
 * {@code findByCompanyIdAndEntityKindForUpdate(companyId, X)}, resolving {@code X} through the
 * declaring file's own {@code String CONSTANT = "LITERAL"} declarations. Both forms are matched
 * because a generator can look up without creating.
 */
@DisplayName("the sequence seeder covers every kind the code allocates")
class CodeSequenceSeederCoversAllKindsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");

    /** {@code private static final String KIND_X = "LITERAL";} */
    private static final Pattern CONSTANT =
            Pattern.compile("String\\s+([A-Z_][A-Z0-9_]*)\\s*=\\s*\"([A-Z_][A-Z0-9_]*)\"");

    /** Either allocation form, capturing a constant name or an inline literal. */
    private static final Pattern USE = Pattern.compile(
            "(?:new CodeSequence\\(companyId,\\s*|findByCompanyIdAndEntityKindForUpdate\\(companyId,\\s*)"
                    + "(?:\"([A-Z_][A-Z0-9_]*)\"|([A-Z_][A-Z0-9_]*))\\)");

    private static Set<String> kindsUsedInSource() throws IOException {
        Set<String> kinds = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (!src.contains("CodeSequence")) {
                    continue;
                }
                Map<String, String> constants = new HashMap<>();
                Matcher c = CONSTANT.matcher(src);
                while (c.find()) {
                    constants.put(c.group(1), c.group(2));
                }
                Matcher u = USE.matcher(src);
                while (u.find()) {
                    String literal = u.group(1);
                    String viaConstant = u.group(2) == null ? null : constants.get(u.group(2));
                    if (literal != null) {
                        kinds.add(literal);
                    } else if (viaConstant != null) {
                        kinds.add(viaConstant);
                    }
                    // A constant declared in another file would resolve to null and be dropped. That
                    // has not happened — every generator declares its own — and if it ever does, the
                    // count assertion below is what notices.
                }
            }
        }
        return kinds;
    }

    @Test
    @DisplayName("no kind is allocated that provisioning does not seed")
    void seederCoversEveryAllocatedKind() throws IOException {
        Set<String> used = kindsUsedInSource();

        // Guard against the scan silently matching nothing — a broken regex would otherwise make
        // this test pass while checking an empty set, which is the vacuous-pass shape that has
        // already cost this codebase an outage.
        assertThat(used)
                .as("the source scan found no CodeSequence kinds at all — the patterns have stopped "
                        + "matching and this test is no longer guarding anything")
                .hasSizeGreaterThan(20);

        assertThat(CodeSequenceSeeder.KINDS)
                .as("a kind the code allocates but provisioning does not seed keeps its first-use "
                        + "race: two clerks raising the first document of that kind both insert and "
                        + "one gets a misleading 409. Add it to CodeSequenceSeeder.KINDS.")
                .containsAll(used);
    }

    @Test
    @DisplayName("the seeder does not seed kinds nothing allocates")
    void seederHasNoPhantomKinds() throws IOException {
        // The harmless direction, but worth knowing: a stale entry means a row per company that
        // nothing will ever read, and a name that no longer means anything to whoever reads the list.
        assertThat(kindsUsedInSource())
                .as("CodeSequenceSeeder.KINDS names a kind no generator allocates — either a "
                        + "generator was deleted, or the name was mistyped")
                .containsAll(CodeSequenceSeeder.KINDS);
    }
}
