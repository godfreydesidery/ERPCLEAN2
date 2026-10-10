package com.erp.modules.gl.domain.dto;

import java.time.LocalDate;

/**
 * Optional body of the re-post action (ACC-02).
 *
 * @param postingDate post on this date instead of the original one — for a failure whose period
 *                    is closed for good; null keeps the original posting date
 */
public record GlPostingRepostRequest(LocalDate postingDate) {}
