package com.erp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Regression for ISSUES-REGISTER #12 — the per-company {@code gl_configs}/seed CROSS-JOIN inserts in
 * V10/V11/V12 built a {@code uid} by concatenating the raw {@code config_key} (e.g.
 * {@code OPENING_BALANCE_EQUITY}), overflowing {@code uid VARCHAR(26)} — but ONLY when the seed runs
 * on a DB that already has companies (a keep-data upgrade). The normal test path applies all
 * migrations to an EMPTY database (no companies at migration time), so those seeds insert zero rows
 * and the overflow was invisible.
 *
 * <p>This test reproduces a keep-data upgrade in an isolated schema on the shared container:
 * migrate to V9 (pre-GL), insert an organisation + company (a "live" DB), then migrate to head.
 * Pre-fix this threw {@code value too long for type character varying(26)} on V10/V11; post-fix the
 * seed uids are bounded (md5-based) and the upgrade succeeds. Also asserts every seeded {@code uid}
 * is ≤26 chars and that gl_configs were actually seeded for the pre-existing company.
 */
class MigrationKeepDataIT extends PostgresIntegrationTest {

    private static final String SCHEMA = "keepdata_test";
    private static final String ORG_UID = "KEEPDATA_ORG_000000000001";   // 25 chars (<=26)
    private static final String CO_UID  = "KEEPDATA_CO_0000000000001";   // 25 chars (<=26)

    @Test
    void migrationsApplyOntoExistingCompany_noUidOverflow() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        String user = POSTGRES.getUsername();
        String pass = POSTGRES.getPassword();

        // Clean, isolated schema (does not touch the shared 'public' schema the other ITs use).
        try (Connection c = DriverManager.getConnection(url, user, pass); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
        }

        // 1) Migrate the isolated schema up to V9 (the state before GL/V10).
        Flyway.configure()
                .dataSource(url, user, pass)
                .schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        // 2) Insert an organisation + company — simulate an existing, live database being upgraded.
        try (Connection c = DriverManager.getConnection(url, user, pass); Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
            s.execute("INSERT INTO organisations (uid, name) VALUES ('" + ORG_UID + "', 'KeepData Org')");
            s.execute("INSERT INTO companies (uid, organisation_id, code, name) "
                    + "SELECT '" + CO_UID + "', id, 'KD01', 'KeepData Co' "
                    + "FROM organisations WHERE uid = '" + ORG_UID + "'");
        }

        // 3) Migrate to head (V10..V13). The per-company seeds now run for the existing company —
        //    pre-fix this threw "value too long for type character varying(26)".
        assertThatCode(() ->
                Flyway.configure()
                        .dataSource(url, user, pass)
                        .schemas(SCHEMA).defaultSchema(SCHEMA)
                        .locations("classpath:db/migration")
                        .target("latest")
                        .load()
                        .migrate()
        ).doesNotThrowAnyException();

        // 4) The existing company got its gl_configs seeded, and every seeded uid fits VARCHAR(26).
        try (Connection c = DriverManager.getConnection(url, user, pass); Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) AS n, coalesce(max(length(uid)),0) AS maxlen FROM gl_configs")) {
                rs.next();
                int n = rs.getInt("n");
                int maxLen = rs.getInt("maxlen");
                assertThat(n).as("gl_configs seeded for the pre-existing company").isGreaterThan(0);
                assertThat(maxLen).as("every gl_config uid fits VARCHAR(26)").isLessThanOrEqualTo(26);
            }
            // Sanity: long config keys are present (the ones that overflowed pre-fix).
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) AS n FROM gl_configs WHERE config_key IN "
                    + "('ACCOUNTS_RECEIVABLE','OPENING_BALANCE_EQUITY','BAD_DEBT_EXPENSE')")) {
                rs.next();
                assertThat(rs.getInt("n")).as("long-named config keys seeded").isGreaterThan(0);
            }
        } finally {
            try (Connection c = DriverManager.getConnection(url, user, pass); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }
}
