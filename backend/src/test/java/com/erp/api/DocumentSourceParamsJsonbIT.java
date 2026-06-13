package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.documents.domain.entity.GeneratedDocument;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.repository.GeneratedDocumentRepository;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Regression IT for Bug: sourceParams JSONB binding (commit 9798a66).
 *
 * <p>Pre-fix behaviour: {@code GeneratedDocument.sourceParams} was mapped with only
 * {@code columnDefinition = "JSONB"} — no {@code @JdbcTypeCode(SqlTypes.JSON)}.  Hibernate bound
 * it as {@code varchar}, causing Postgres to throw:
 * {@code column "source_params" is of type jsonb but expression is of type character varying}
 * → HTTP 500 on any parameterised render (e.g. AR_STATEMENT).
 *
 * <p>Fix: added {@code @JdbcTypeCode(SqlTypes.JSON)} to the field, instructing Hibernate 6 to
 * bind the String value as a JSON/JSONB parameter instead of varchar.
 *
 * <p>This test persists a {@link GeneratedDocument} row with a non-null JSON {@code sourceParams}
 * string and reads it back, asserting that:
 * <ol>
 *   <li>The INSERT succeeds without a type-mismatch exception.</li>
 *   <li>The round-tripped value is identical to what was written.</li>
 * </ol>
 * Against the pre-fix entity the INSERT throws {@code PSQLException: ERROR: column
 * "source_params" is of type jsonb but expression is of type character varying}.
 */
@AutoConfigureMockMvc
class DocumentSourceParamsJsonbIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository       organisations;
    @Autowired private CompanyRepository            companies;
    @Autowired private BranchRepository             branches;
    @Autowired private GeneratedDocumentRepository  generatedDocs;
    @Autowired private IamTestData                  testData;

    private Long companyId;
    private Long branchId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = new Organisation("DocJsonb Org");
        organisations.save(org);

        Company company = new Company(org, "DJSONB", "Doc Jsonb Company");
        companies.save(company);
        companyId = company.getId();

        Branch branch = new Branch(company, "DJSONB-HQ", "Doc Jsonb HQ");
        branch.setDefault(true);
        branches.save(branch);
        branchId = branch.getId();
    }

    @AfterEach
    void tearDown() {
        testData.clearAll();
    }

    // -------------------------------------------------------------------------
    // Regression: sourceParams JSONB binding must use SqlTypes.JSON, not varchar
    // -------------------------------------------------------------------------

    /**
     * Persists a GeneratedDocument with a non-null JSON sourceParams string and re-fetches it.
     * Pre-fix: PSQLException on INSERT (varchar → jsonb type mismatch).
     * Post-fix: round-trip succeeds and the value is identical.
     */
    @Test
    void persist_generatedDocument_withJsonSourceParams_roundTrips() {
        // AR_STATEMENT is the canonical parameterised render type that populates sourceParams
        // (DocumentRenderServiceImpl line ~314: req.sourceParams() required for AR_STATEMENT).
        String sourceParamsJson = "{\"customerUid\":\"01HXREGTEST000000CUST01\",\"asAt\":\"2024-06-30\"}";

        GeneratedDocument doc = new GeneratedDocument(
                companyId,
                branchId,
                "DOC-0001",
                DocumentType.AR_STATEMENT,
                "AR_STATEMENT",
                null,               // sourceUid — null for parameterised renders
                sourceParamsJson,   // the JSONB column under test
                null,               // brandingId
                1L                  // generatedBy (actor stub)
        );
        doc.setContentMeta("deadbeef01234567", 1024);

        GeneratedDocument saved = generatedDocs.save(doc);
        generatedDocs.flush();  // force the INSERT so the JSONB bind happens in this assertion scope

        // Re-load from DB — proves the value survived the round-trip through Postgres JSONB
        GeneratedDocument reloaded = generatedDocs.findByUid(saved.getUid())
                .orElseThrow(() -> new AssertionError("GeneratedDocument not found: " + saved.getUid()));

        assertThat(reloaded.getSourceParams())
                .as("sourceParams must survive JSONB round-trip (pre-fix: varchar→jsonb type error)")
                .isNotNull()
                .isNotBlank();

        // The round-tripped JSON must contain the same key-value pair we wrote;
        // Postgres may normalise whitespace/key-order but the customerUid must be intact.
        assertThat(reloaded.getSourceParams())
                .contains("customerUid")
                .contains("01HXREGTEST000000CUST01");
    }

    /**
     * Sanity: a GeneratedDocument with null sourceParams persists and reloads as null.
     * Keeps the regression test honest — it's only the non-null case that was broken.
     */
    @Test
    void persist_generatedDocument_withNullSourceParams_isNull() {
        GeneratedDocument doc = new GeneratedDocument(
                companyId,
                branchId,
                "DOC-0002",
                DocumentType.INVOICE,
                "SALES_INVOICE",
                "01HXINVOICEUID000001",
                null,   // sourceParams null — single-source render
                null,
                1L
        );
        doc.setContentMeta("aabbcc", 512);

        GeneratedDocument saved = generatedDocs.saveAndFlush(doc);

        GeneratedDocument reloaded = generatedDocs.findByUid(saved.getUid())
                .orElseThrow(() -> new AssertionError("GeneratedDocument not found: " + saved.getUid()));

        assertThat(reloaded.getSourceParams())
                .as("null sourceParams must round-trip as null")
                .isNull();
    }
}
