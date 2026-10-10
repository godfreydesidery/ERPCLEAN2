package com.erp.modules.cashbank.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When the cash book started carrying sales (gap review wave 3, ARC-08 / ARC-01): the moment
 * migration V106 — which admitted the SALE_TENDER / SALE_REFUND / POS_PAYOUT / POS_VARIANCE rows —
 * was applied to this database. Read from Flyway's own history, so it is the same instant on
 * every node and needs no new column or setting. Cached once found (it never changes).
 *
 * <p>Before this instant a till that takes sales had its takings, payouts and POS over/short in
 * the GL only; after it, the cash book mirrors them. Cash counts use it to refuse a business day
 * the cash book cannot speak for and to carry the frozen pre-go-live GL/cash-book gap.
 */
@Component
public class CashBookGoLive {

    private static final Logger log = LoggerFactory.getLogger(CashBookGoLive.class);

    static final String VERSION = "106";

    private final JdbcTemplate jdbc;
    private volatile Instant cached;

    public CashBookGoLive(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The instant V106 was applied; empty when it cannot be read (then callers stay cautious). */
    public Optional<Instant> since() {
        Instant at = cached;
        if (at != null) {
            return Optional.of(at);
        }
        try {
            List<Timestamp> rows = jdbc.queryForList(
                    "SELECT installed_on FROM flyway_schema_history WHERE version = ? AND success",
                    Timestamp.class, VERSION);
            if (rows.isEmpty() || rows.get(0) == null) {
                return Optional.empty();
            }
            cached = rows.get(0).toInstant();
            return Optional.of(cached);
        } catch (RuntimeException ex) {
            log.warn("CashBookGoLive: cannot read the V{} install time — {}", VERSION, ex.toString());
            return Optional.empty();
        }
    }
}
