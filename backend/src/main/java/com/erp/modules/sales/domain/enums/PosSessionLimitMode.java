package com.erp.modules.sales.domain.enums;

/**
 * How a company limits the age of an open till session before it stops taking sales (V113).
 *
 * <p>Stored as one column, {@code sales_settings.pos_session_max_open_hours}:
 * {@link #SERVER_SETTING} = {@code NULL}, {@link #NO_LIMIT} = {@code 0}, {@link #HOURS} = 12–168.
 * The mode exists on the API so "leave it alone" (mode absent) can never be confused with "use the
 * server setting" — a JSON null on a single nullable field cannot carry both meanings.
 */
public enum PosSessionLimitMode {
    /** Use the server setting {@code erp.pos.session.max-open-age} (36 h unless changed). */
    SERVER_SETTING,
    /** No limit: a session may stay open indefinitely. Its Z-read may then span several days. */
    NO_LIMIT,
    /** This company's own limit, in hours (12–168). */
    HOURS
}
