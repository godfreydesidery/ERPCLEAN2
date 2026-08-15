package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/**
 * Every {@code application*.yml} must parse — including the ones no test environment ever loads.
 *
 * <h2>Why this exists</h2>
 *
 * {@code application-prod.yml} is read by the {@code prod} profile and nothing else. Local runs use
 * {@code dev} and QA uses {@code qa}, so <b>the only environment that ever parses it is the paying
 * customer's</b>. A second top-level {@code spring:} key was appended to it, which is a duplicate-key
 * error that Spring's YAML loader raises before reading a single property — the application died at
 * startup. It shipped as 1.8.2 and took the live installation down; every test, and QA, had passed.
 *
 * <p>This uses {@link YamlPropertySourceLoader} - the public wrapper Spring Boot itself calls at startup, which delegates to the package-private OriginTrackedYamlLoader visible in the 1.8.2 crash stack -, rather
 * than a plain YAML parse. That matters: it is the loader's duplicate-key handling that failed, so a
 * more permissive parser would pass here and still break in production — the test would then be
 * worse than nothing, because it would look like coverage.
 *
 * <p>It deliberately does NOT start a Spring context: a context needs a database and a full profile,
 * which is what made this file untested in the first place. Parsing is cheap, needs nothing, and is
 * exactly the failure that occurred.
 */
@DisplayName("every application*.yml parses with Spring's own loader")
class ApplicationYamlParsesTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static List<Path> configFiles() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            return files
                    .filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("each file loads without a parse or duplicate-key error")
    void everyConfigFileParses() throws IOException {
        List<Path> files = configFiles();

        // If this ever finds nothing, the test has silently stopped guarding anything — the same
        // vacuous-pass shape that has bitten this codebase before.
        assertThat(files)
                .as("no application*.yml found under %s — this test would pass while checking nothing",
                        RESOURCES.toAbsolutePath())
                .isNotEmpty();

        for (Path file : files) {
            assertThatCode(() -> new YamlPropertySourceLoader().load(file.getFileName().toString(), new FileSystemResource(file)))
                    .as("%s must parse with the loader Spring Boot actually uses at startup. A "
                            + "duplicate top-level key here kills the application before it reads "
                            + "any property — and application-prod.yml is parsed by no environment "
                            + "except the customer's.", file.getFileName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the prod profile is among them — it is the one nothing else loads")
    void prodConfigIsCovered() throws IOException {
        assertThat(configFiles().stream().map(p -> p.getFileName().toString()))
                .as("application-prod.yml is the file with no other safety net; if it stops being "
                        + "picked up here, this test has lost the case it was written for")
                .contains("application-prod.yml");
    }
}
