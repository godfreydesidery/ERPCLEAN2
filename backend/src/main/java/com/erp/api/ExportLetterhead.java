package com.erp.api;

import com.erp.modules.documents.domain.dto.DocumentBrandingDto;
import com.erp.modules.documents.service.DocumentBrandingService;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The letterhead every sub-ledger export (customer / supplier statement, ageing, cash statement,
 * VAT return, WHT register) prints at its head and foot: the company block, the logo, and the
 * "Printed On / At / By" footprint.
 *
 * <p>Lives in the web layer because it only assembles what the export controllers hand to the
 * {@code TabularExporter}; the data itself comes from {@link ReportCompanyHeaderQuery} (the shared
 * company block) and the document branding (the logo). Callers must have passed the read's own
 * tenant check BEFORE asking for the letterhead — the company block is not itself a scoped read.
 */
@Component
public class ExportLetterhead {

    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm:ss a");

    private final ReportCompanyHeaderQuery companyHeaders;
    private final DocumentBrandingService  branding;

    public ExportLetterhead(ReportCompanyHeaderQuery companyHeaders, DocumentBrandingService branding) {
        this.companyHeaders = companyHeaders;
        this.branding       = branding;
    }

    /** The company block and logo for an export whose read has already passed its tenant check. */
    public Letterhead forCompany(Long companyId) {
        return new Letterhead(companyHeaders.forCompany(companyId), logoOf(companyId));
    }

    /**
     * The company's logo, or null when it has never uploaded one — the document then prints
     * text-only. A branding lookup must never be the reason a statement cannot be printed, so any
     * failure degrades to no logo.
     */
    private String logoOf(Long companyId) {
        try {
            DocumentBrandingDto b = branding.getForCompany(companyId);
            return b != null ? b.logoDataUri() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The company block plus its logo (null when none was uploaded). */
    public record Letterhead(ReportCompanyHeaderDto company, String logoDataUri) {}

    // -------------------------------------------------------------------------
    // Formatting helpers shared by the export flatteners
    // -------------------------------------------------------------------------

    /**
     * The company lines at the head of a printed document: name, postal address, phone, email, TIN
     * and VRN — only the ones the company has filled in, so a blank never prints a bare label.
     */
    static List<String> companyLines(ReportCompanyHeaderDto company) {
        List<String> lines = new ArrayList<>();
        if (company == null) {
            return lines;
        }
        addIfPresent(lines, "", company.name());
        if (company.legalName() != null && !company.legalName().isBlank()
                && !company.legalName().equals(company.name())) {
            lines.add(company.legalName());
        }
        String address = joinAddress(company);
        if (address != null) {
            lines.add(address);
        }
        addIfPresent(lines, "Tel: ", company.contactPhone());
        addIfPresent(lines, "Email: ", company.contactEmail());
        addIfPresent(lines, "TIN: ", company.taxId());
        addIfPresent(lines, "VRN: ", company.vrn());
        return lines;
    }

    /** The plain "generated" timestamp printed at the head. */
    static String generatedAt(ZonedDateTime now) {
        return now.format(DATE_FMT) + " " + now.format(TIME_FMT);
    }

    /**
     * Who produced this document and when — how an argument about which printed copy is current
     * gets settled. The name is the caller's username, which is what {@code RequestContext} carries.
     */
    static String printFootprint(ReportCompanyHeaderDto company, ZonedDateTime now) {
        RequestContext.Principal p = RequestContext.get();
        StringBuilder sb = new StringBuilder()
                .append("Printed On: ").append(now.format(DATE_FMT))
                .append("    Printed At: ").append(now.format(TIME_FMT));
        if (p != null && p.username() != null && !p.username().isBlank()) {
            sb.append("    Printed By: ").append(p.username());
        }
        if (company != null && company.name() != null && !company.name().isBlank()) {
            sb.append("    Printed From: ").append(company.name());
        }
        return sb.toString();
    }

    /** Money cell: thousands separators, two decimals; null prints blank, never 0.00. */
    static String fmtAmt(BigDecimal amount) {
        return amount != null ? String.format("%,.2f", amount) : "";
    }

    /** Money cell that prints blank for a zero — for debit/credit columns where only one side is used. */
    static String fmtAmtOrBlank(BigDecimal amount) {
        return amount == null || amount.signum() == 0 ? "" : String.format("%,.2f", amount);
    }

    static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /** The file download response for an export. */
    static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                .body(result.content());
    }

    private static void addIfPresent(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + value);
        }
    }

    private static String joinAddress(ReportCompanyHeaderDto c) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {
                c.addressLine1(), c.addressLine2(), c.city(), c.region(), c.country()}) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
