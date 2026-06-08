package com.erp.modules.routes.service;

import com.erp.modules.products.domain.entity.CodeSequence;
import com.erp.modules.products.repository.CodeSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-assigned route code generator (ADR-0012 D-7).
 *
 * <p>Reuses the shipped generic {@code code_sequence} table (V3, ADR-0007 D-6) with
 * {@code entity_kind = 'ROUTE'} — no new numbering table. Allocates the next per-(company, ROUTE)
 * value under a {@code SELECT ... FOR UPDATE} row lock and formats it as {@code ROUTE-%04d}.
 *
 * <p>Must be called within an active write transaction (joins via {@code REQUIRED}).
 * The unique index {@code uq_route_company_code} is the DB backstop against generator bugs.
 */
@Component
public class RouteCodeGenerator {

    private static final String ENTITY_KIND = "ROUTE";

    private final CodeSequenceRepository sequences;

    public RouteCodeGenerator(CodeSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Allocates the next route code for the given company.
     *
     * @param companyId the owning company id
     * @return formatted code, e.g. {@code ROUTE-0001}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(Long companyId) {
        CodeSequence seq = sequences
                .findByCompanyIdAndEntityKindForUpdate(companyId, ENTITY_KIND)
                .orElseGet(() -> sequences.saveAndFlush(new CodeSequence(companyId, ENTITY_KIND)));

        long value = seq.consumeNext();
        return "ROUTE-" + String.format("%04d", value);
    }
}
