package com.erp.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runtime reader for the screen-read closure manifest (ADR-0047 Grant-time validator).
 *
 * <p>Loads {@code /security/screen-read-closure.json} once on startup from the classpath
 * (main-resources) and exposes the parsed screens. All consumers — currently
 * {@code RoleReadClosureServiceImpl} — call {@link #screens()} to obtain the immutable view.
 * No other class reads the JSON file at runtime.
 *
 * <p>Fails fast on startup if the manifest is missing or malformed, so drift is caught
 * at boot time, not at request time.
 */
@Component
public class ScreenReadClosureManifest {

    private static final Logger log = LoggerFactory.getLogger(ScreenReadClosureManifest.class);
    private static final String MANIFEST_PATH = "/security/screen-read-closure.json";

    private final ObjectMapper objectMapper;

    /** Immutable list populated once by {@link #load()}. */
    private List<Screen> screens = List.of();

    public ScreenReadClosureManifest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        try (InputStream is = getClass().getResourceAsStream(MANIFEST_PATH)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Screen-read closure manifest not found on classpath: " + MANIFEST_PATH
                                + " — the manifest must exist at"
                                + " backend/src/main/resources/security/screen-read-closure.json");
            }
            JsonNode root = objectMapper.readTree(is);
            List<Screen> parsed = new ArrayList<>();
            for (JsonNode s : root.path("screens")) {
                String screenId = s.path("screen").asText();
                String accessPerm = s.path("accessPermission").asText();
                List<ReadEntry> reads = new ArrayList<>();
                for (JsonNode r : s.path("reads")) {
                    reads.add(new ReadEntry(
                            r.path("code").asText(),
                            r.path("necessity").asText(),
                            r.path("gate").asText()));
                }
                parsed.add(new Screen(screenId, accessPerm, Collections.unmodifiableList(reads)));
            }
            this.screens = Collections.unmodifiableList(parsed);
            log.info("Loaded screen-read closure manifest: {} screen(s)", this.screens.size());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to parse screen-read closure manifest at " + MANIFEST_PATH, e);
        }
    }

    /** Returns the immutable list of manifest screens. */
    public List<Screen> screens() {
        return screens;
    }

    // -------------------------------------------------------------------------
    // Value types (package-visible for test access if needed)
    // -------------------------------------------------------------------------

    /**
     * One screen entry from the manifest.
     *
     * @param screen            screen identifier (e.g. {@code ar.record-receipt})
     * @param accessPermission  the permission code that opens the screen
     * @param reads             the supporting reads the screen fires on load
     */
    public record Screen(String screen, String accessPermission, List<ReadEntry> reads) {}

    /**
     * One read-dependency entry within a screen.
     *
     * @param code       permission code the controller gate checks
     * @param necessity  {@code required} or {@code optional}
     * @param gate       gate form: {@code has}, {@code scoped}, {@code hasOrMember},
     *                   {@code scopedOrMember}, or {@code disjunction}
     */
    public record ReadEntry(String code, String necessity, String gate) {}
}
