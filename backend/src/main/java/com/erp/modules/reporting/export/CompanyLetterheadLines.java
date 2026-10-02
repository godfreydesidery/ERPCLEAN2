package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the company block into the header lines a {@link TabularRenderModel} prints above a table —
 * name, postal address, Tel, Email, TIN, VRN, each only when present. Same lines and order as the
 * Sales Report's letterhead, so the payroll statutory and fixed-asset exports print the same way.
 */
public final class CompanyLetterheadLines {

    private CompanyLetterheadLines() {}

    public static List<String> of(ReportCompanyHeaderDto company) {
        List<String> lines = new ArrayList<>();
        if (company == null) {
            return lines;
        }
        if (company.name() != null) {
            lines.add(company.name());
        }
        StringBuilder address = new StringBuilder();
        for (String part : new String[] {company.addressLine1(), company.addressLine2(),
                company.city(), company.region(), company.country()}) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (address.length() > 0) {
                address.append(", ");
            }
            address.append(part);
        }
        if (address.length() > 0) {
            lines.add(address.toString());
        }
        if (company.contactPhone() != null) {
            lines.add("Tel: " + company.contactPhone());
        }
        if (company.contactEmail() != null) {
            lines.add("Email: " + company.contactEmail());
        }
        if (company.taxId() != null) {
            lines.add("TIN: " + company.taxId());
        }
        if (company.vrn() != null) {
            lines.add("VRN: " + company.vrn());
        }
        return lines;
    }
}
