package com.erp.modules.stock.events;

import java.util.HashMap;
import java.util.Map;

/**
 * Allocates a distinct, deterministic {@code source_event_uid} for every stock movement a single
 * domain event posts.
 *
 * <p><strong>Why this exists.</strong> {@code stock_movements} carries the unique constraint
 * {@code uq_stock_movement_source_event (source_event_uid, product_id)}, and
 * {@link com.erp.modules.stock.service.StockPostingServiceImpl} short-circuits on that pair as an
 * idempotency backstop. Handlers used to pass the raw {@code domain_events.uid} for every line of
 * the document, so <em>one document = one key</em>: the second line of the same product looked like
 * a redelivery of the first and was silently dropped — quantity and ledger under-posted while the
 * invoice/receipt total, the COGS journal and {@code on_hand_value} all counted every line. A sale
 * of 3 + 4 of one product deducted 3; a GRN receiving one product in two lots landed one lot.
 *
 * <p><strong>The key shape.</strong> {@code source_event_uid} is {@code VARCHAR(26)} and a ULID is
 * exactly 26 chars, so a suffix cannot simply be appended (PostgreSQL raises {@code 22001 value too
 * long} and rolls the dispatch back). Following the pattern already proven by
 * {@link TransferDispatchStockHandler} (STOCK-039), the key is the event uid truncated to 21 chars,
 * plus a 1-char leg code, plus a 4-hex-digit index — 26 chars exactly.
 *
 * <p><strong>Stability.</strong> The index is the <em>per-product occurrence number</em> within this
 * event: the n-th posting of product P always gets index n, whatever else the document contains.
 * It is derived from position alone — never from a timestamp or a random value — so a redelivery of
 * the same event regenerates exactly the same keys and the backstop still recognises the work as
 * already applied. Keying per product (rather than a single running counter over all postings) means
 * an unrelated product appearing or disappearing from the iteration cannot shift another product's
 * keys.
 *
 * <p>Leg codes let one handler run several independent sequences over the same lines — the transfer
 * handlers post an OUT and an IN leg per line and need distinct keys for each.
 *
 * <p>Not thread-safe; one instance per {@code handle(event)} invocation.
 */
final class MovementSourceKeys {

    /** Retained prefix of the event uid: 21 + 1 leg char + 4 hex = 26, the column width. */
    private static final int PREFIX_LEN = 21;

    /** Leg code for handlers that post a single movement sequence per line. */
    static final char SINGLE_LEG = 'L';

    private final String base;
    private final Map<Long, Integer> countByProduct = new HashMap<>();

    private MovementSourceKeys(String eventUid, char legCode) {
        String uid = eventUid == null ? "" : eventUid;
        this.base = (uid.length() > PREFIX_LEN ? uid.substring(0, PREFIX_LEN) : uid) + legCode;
    }

    /** Sequence for a handler that posts one movement per line. */
    static MovementSourceKeys forEvent(String eventUid) {
        return new MovementSourceKeys(eventUid, SINGLE_LEG);
    }

    /**
     * Sequence for one leg of a handler that posts several movements per line.
     * Each leg needs its own instance and its own {@code legCode}; the legs advance in lockstep,
     * so the n-th line's OUT and IN keys share an index and differ only in the leg character.
     */
    static MovementSourceKeys forLeg(String eventUid, char legCode) {
        return new MovementSourceKeys(eventUid, legCode);
    }

    /**
     * The key for the next movement of {@code productId} in this sequence. Must be called exactly
     * once per intended posting, in document order, including for postings that are then skipped —
     * skipping the call would shift every later key for that product and break redelivery matching.
     */
    String nextFor(Long productId) {
        int index = countByProduct.merge(productId, 1, Integer::sum) - 1;
        return base + String.format("%04x", index);
    }
}
