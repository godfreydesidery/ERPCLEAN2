package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** "It is me": the signed-in user's own password, re-typed at a shared till (ADR-0067 D-4). */
public record ConfirmOwnPasswordRequest(@NotBlank String password) {}
