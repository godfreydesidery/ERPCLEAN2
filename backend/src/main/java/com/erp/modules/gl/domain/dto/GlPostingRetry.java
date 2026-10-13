package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

/**
 * A recorded GL posting failure, handed back to the module that owns its poster so it can
 * re-invoke that poster (ACC-02).
 *
 * @param kind           the poster that failed
 * @param companyId      owning company
 * @param branchId       branch of the source document (nullable)
 * @param sourceType     journal source type
 * @param sourceRef      source document uid
 * @param documentNumber human-readable document number (nullable)
 * @param postingDate    the date to post on now (the original date unless the user chose another)
 * @param args           the poster's remaining arguments, as recorded
 */
public record GlPostingRetry(
        GlPostingFailureKind kind,
        Long companyId,
        Long branchId,
        JournalSourceType sourceType,
        String sourceRef,
        String documentNumber,
        LocalDate postingDate,
        JsonNode args
) {}
