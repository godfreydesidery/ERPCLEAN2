package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.platform.bulk.ImportContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PRD-12: re-uploading a customer/supplier sheet must update, not duplicate. */
class PartyImportMatcherTest {

    @Test
    void tinMatchWins() {
        String hit = PartyImportMatcher.match("customer", "Mama Ashura Shop", "123-456-789",
                t -> List.of("BY-TIN"), n -> List.of("BY-NAME"));
        assertThat(hit).isEqualTo("BY-TIN");
    }

    @Test
    void fallsBackToTheName_whenNoTinMatches() {
        String hit = PartyImportMatcher.match("customer", "Mama Ashura Shop", "123",
                t -> List.of(), n -> List.of("BY-NAME"));
        assertThat(hit).isEqualTo("BY-NAME");
    }

    @Test
    void noMatch_meansCreate() {
        assertThat(PartyImportMatcher.<String>match("customer", "New Duka", null,
                t -> List.of(), n -> List.of())).isNull();
    }

    @Test
    void ambiguousName_asksForTheCode() {
        assertThatThrownBy(() -> PartyImportMatcher.match("customer", "Mama Ashura", null,
                t -> List.of(), n -> List.of("A", "B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Code column");
    }

    @Test
    void repeatedNameInTheSameFile_isCaughtAtValidate() {
        ImportContext ctx = new ImportContext();
        PartyImportMatcher.claimInFile(ctx, "customer", "Mama Ashura Shop", null);
        assertThatThrownBy(() -> PartyImportMatcher.claimInFile(ctx, "customer", "  mama ashura   shop ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }
}
