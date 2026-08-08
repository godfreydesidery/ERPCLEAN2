package com.erp.modules.stock.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MovementSourceKeys} — the per-posting {@code source_event_uid} allocator
 * behind the "repeated product loses all but the first movement" fix.
 *
 * <p>Three properties carry the whole fix and each is pinned here: keys must be <b>distinct</b> per
 * posting of a product (or the DB backstop silently drops the posting), exactly <b>26 chars</b> (the
 * column is {@code VARCHAR(26)} and PostgreSQL raises {@code 22001} on overflow, rolling the whole
 * dispatch back), and <b>stable</b> across a replay of the same event (or redelivery double-applies
 * instead of being a no-op — which is the entire point of an idempotency key).
 */
class MovementSourceKeysTest {

    /** A real 26-char ULID, the production width of {@code domain_events.uid}. */
    private static final String EVENT_UID = "01KVJT7VQ0XWKE53X4MGM87BYN";

    private static final Long PRODUCT_A = 100L;
    private static final Long PRODUCT_B = 200L;

    @Test
    void repeatedPostingsOfTheSameProductGetDistinctKeys() {
        MovementSourceKeys keys = MovementSourceKeys.forEvent(EVENT_UID);

        String first  = keys.nextFor(PRODUCT_A);
        String second = keys.nextFor(PRODUCT_A);
        String third  = keys.nextFor(PRODUCT_A);

        // The defect in one line: these used to be the same string, so the backstop treated line 2
        // as a redelivery of line 1 and the quantity never moved.
        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
    }

    @Test
    void everyKeyIsExactly26CharsForARealUlid() {
        MovementSourceKeys keys = MovementSourceKeys.forEvent(EVENT_UID);

        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            generated.add(keys.nextFor(PRODUCT_A));
        }

        assertThat(generated).allSatisfy(key -> assertThat(key)
                .as("source_event_uid must fit VARCHAR(26) exactly")
                .hasSize(26));
    }

    @Test
    void keysAreStableAcrossAReplayOfTheSameEvent() {
        // Idempotency depends on this: a redelivered event must regenerate the very keys already in
        // the ledger, so the backstop recognises the work as done. A key derived from a timestamp or
        // a random value would look brand new and double-apply every line.
        List<String> firstRun  = allocate(MovementSourceKeys.forEvent(EVENT_UID));
        List<String> replayRun = allocate(MovementSourceKeys.forEvent(EVENT_UID));

        assertThat(replayRun).containsExactlyElementsOf(firstRun);
    }

    @Test
    void indicesAreCountedPerProduct_soAnUnrelatedProductCannotShiftThem() {
        MovementSourceKeys keys = MovementSourceKeys.forEvent(EVENT_UID);

        String aFirst = keys.nextFor(PRODUCT_A);
        keys.nextFor(PRODUCT_B);              // an unrelated product between A's two postings
        String aSecond = keys.nextFor(PRODUCT_A);

        MovementSourceKeys withoutB = MovementSourceKeys.forEvent(EVENT_UID);
        assertThat(aFirst).isEqualTo(withoutB.nextFor(PRODUCT_A));
        assertThat(aSecond).isEqualTo(withoutB.nextFor(PRODUCT_A));
    }

    @Test
    void differentLegsOfTheSameLineGetDifferentKeys() {
        // Transfers post an OUT and an IN for one line; sharing a key would suppress the second.
        MovementSourceKeys out = MovementSourceKeys.forLeg(EVENT_UID, 'D');
        MovementSourceKeys in  = MovementSourceKeys.forLeg(EVENT_UID, 'd');

        assertThat(out.nextFor(PRODUCT_A)).isNotEqualTo(in.nextFor(PRODUCT_A));
    }

    @Test
    void differentEventsGetDifferentKeys() {
        // Only the first 21 chars of the event uid survive into the key. A ULID's leading 10 chars
        // are its millisecond timestamp and the rest are random, so 21 chars still carry ~55 bits of
        // entropy — two distinct events colliding would need the same millisecond AND the same 55
        // random bits AND the same product. Two ordinary events differ.
        String otherEventUid = "01KVJT7VQ0XWKE53Z9QRTM87BYN";

        assertThat(MovementSourceKeys.forEvent(EVENT_UID).nextFor(PRODUCT_A))
                .isNotEqualTo(MovementSourceKeys.forEvent(otherEventUid).nextFor(PRODUCT_A));
    }

    @Test
    void shortEventUidDoesNotOverflowOrThrow() {
        // Defensive: unit tests and older fixtures use short synthetic event uids.
        MovementSourceKeys keys = MovementSourceKeys.forEvent("EVT-1");

        assertThat(keys.nextFor(PRODUCT_A)).hasSizeLessThanOrEqualTo(26);
        assertThat(keys.nextFor(PRODUCT_A)).hasSizeLessThanOrEqualTo(26);
    }

    private static List<String> allocate(MovementSourceKeys keys) {
        return List.of(
                keys.nextFor(PRODUCT_A),
                keys.nextFor(PRODUCT_B),
                keys.nextFor(PRODUCT_A));
    }
}
