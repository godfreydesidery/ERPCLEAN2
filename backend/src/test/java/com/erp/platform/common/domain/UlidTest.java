package com.erp.platform.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void next_is26CrockfordChars() {
        String ulid = Ulid.next();
        assertThat(ulid).hasSize(26);
        assertThat(ulid).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void next_isUniqueAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(Ulid.next());
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void next_isTimeSortable() {
        String earlier = Ulid.next(1_000_000_000_000L);
        String later = Ulid.next(2_000_000_000_000L);
        assertThat(earlier.compareTo(later)).isNegative();
    }
}
