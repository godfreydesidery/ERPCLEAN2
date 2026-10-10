package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The scope a report applies: every branch, one branch, or a branch-limited caller's own set. */
class BranchReadScopeTest {

    @Test
    void everyBranchAddsNoPredicateAndSaysAllBranches() {
        BranchReadScope s = BranchReadScope.everyBranch();
        assertThat(s.sql("i.branch_id")).isEmpty();
        assertThat(s.includes(42L)).isTrue();
        assertThat(s.headerLine()).isEqualTo("Branch: All branches");
        assertThat(s.limitedToAssigned()).isFalse();
    }

    @Test
    void assignedBranchesNarrowToTheSetAndNameThem() {
        BranchReadScope s = BranchReadScope.assigned(List.of(3L, 7L), List.of("Arusha", "Moshi"));
        assertThat(s.sql("i.branch_id")).isEqualTo(" AND i.branch_id IN (3, 7)");
        assertThat(s.includes(3L)).isTrue();
        assertThat(s.includes(9L)).isFalse();
        assertThat(s.includes(null)).isFalse();
        assertThat(s.label()).isEqualTo("Branches: Arusha, Moshi");
        assertThat(s.headerLine()).isEqualTo("Branches: Arusha, Moshi");
        assertThat(s.limitedToAssigned()).isTrue();
    }

    @Test
    void noAssignmentReadsNothing() {
        BranchReadScope s = BranchReadScope.assigned(List.of(), List.of());
        assertThat(s.sql("x.branch_id")).isEqualTo(" AND 1 = 0");
        assertThat(s.label()).isEqualTo("Branches: none assigned");
    }

    @Test
    void oneFilteredBranchIsNamed() {
        BranchReadScope s = BranchReadScope.single(5L, "Dodoma");
        assertThat(s.sql("b")).isEqualTo(" AND b IN (5)");
        assertThat(s.headerLine()).isEqualTo("Branch: Dodoma");
        assertThat(s.limitedToAssigned()).isFalse();
    }
}
