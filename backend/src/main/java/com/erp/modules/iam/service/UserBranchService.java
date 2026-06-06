package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import java.util.List;

/**
 * Branch assignment — the headline IAM requirement (US-IAM-006, DATA-MODEL §1.9). A user may be
 * assigned to many branches; exactly one is the default. Assignment is decoupled from roles
 * (FR-IAM-24) — a user may be present in a branch without a role; they just can't act there until a
 * {@code user_role} covers it. Controllers depend on this interface (DIP).
 */
public interface UserBranchService {

    /** Assign a user to a branch (optionally as the default). Rejects a duplicate assignment. */
    UserBranchDto assign(AssignBranchRequest request);

    /**
     * Promote an existing assignment to the user's default — clears the previous default in the same
     * transaction (BR-1; the partial unique index is the backstop). The assignment must exist
     * (FR-IAM-17 "default must be assigned" is structural — you can only default a branch you have).
     */
    UserBranchDto setDefault(String userBranchUid);

    /**
     * Remove an assignment. If it was the default, the earliest-assigned remaining branch becomes the
     * new default (FR-IAM-19 auto-fallback, ADR-0001 D-D) — all in one transaction. Removing the last
     * assignment leaves the user with no active branch (read-only state).
     */
    void remove(String userBranchUid);

    /** A user's branch assignments, ordered by assignment time. */
    List<UserBranchDto> listForUser(String userUid);
}
