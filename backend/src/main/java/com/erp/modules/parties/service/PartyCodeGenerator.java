package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.entity.PartyCodeSequence;
import com.erp.modules.parties.repository.PartyCodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-assigned party code generator (ADR-0006 D-7).
 *
 * <p>Allocates the next per-(company, kind) sequence value under a {@code SELECT ... FOR UPDATE}
 * row lock, formats it as {@code PREFIX-%04d}, and returns it — all inside the caller's transaction.
 * The unique index {@code uq_<table>_company_code} is the DB backstop against generator bugs.
 *
 * <p>Prefixes: CUSTOMER → {@code CUST}, SUPPLIER → {@code SUPP}, AGENT → {@code AGNT},
 * OTHER → {@code OTHR}.
 */
@Component
public class PartyCodeGenerator {

    private final PartyCodeSequenceRepository sequences;

    public PartyCodeGenerator(PartyCodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Allocates the next code for the given company and kind. Must be called within an active
     * write transaction (joins the caller's TX via {@code REQUIRED}).
     *
     * @param companyId the owning company id
     * @param kind      one of {@code CUSTOMER}, {@code SUPPLIER}, {@code AGENT}, {@code OTHER}
     * @return formatted code, e.g. {@code CUST-0001}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId, String kind) {
        PartyCodeSequence seq = sequences
                .findByCompanyIdAndPartyKindForUpdate(companyId, kind)
                .orElseGet(() -> sequences.saveAndFlush(new PartyCodeSequence(companyId, kind)));

        long value = seq.consumeNext();
        return prefix(kind) + "-" + String.format("%04d", value);
    }

    private static String prefix(String kind) {
        return switch (kind) {
            case "CUSTOMER" -> "CUST";
            case "SUPPLIER" -> "SUPP";
            case "AGENT"    -> "AGNT";
            case "OTHER"    -> "OTHR";
            default -> throw new IllegalArgumentException("Unknown party kind: " + kind);
        };
    }
}
