package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating an employee next-of-kin (ADR-0040 D-11).
 */
public record UpdateNextOfKinRequest(
        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 60)
        String relationship,

        @Size(max = 40)
        String phone,

        @Email
        @Size(max = 160)
        String email,

        boolean isPrimary
) {}
