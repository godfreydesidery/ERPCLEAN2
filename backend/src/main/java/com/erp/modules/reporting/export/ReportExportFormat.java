package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The cell and letterhead formatting the tabular report exports share.
 *
 * <p>Every report controller used to grow its own private copy of these few helpers (company block,
 * money, quantity). The newer reports call this one instead. The existing controllers are left as
 * they are — rewriting working export code is not part of adding a report.
 *
 * <p>Two rules every helper here keeps: an unknown amount prints BLANK, never "0.00" (an uncosted
 * item is not a free one), and the letterhead only prints the lines the company actually filled in.
 */
public final class ReportExportFormat {

    private ReportExportFormat() {
    }

    /** The company letterhead lines, in print order. Empty when there is no company block. */
    public static List<String> companyLines(ReportCompanyHeaderDto c) {
        List<String> lines = new ArrayList<>();
        if (c == null) {
            return lines;
        }
        if (c.name() != null && !c.name().isBlank()) {
            lines.add(c.name());
        }
        String address = joinAddress(c);
        if (address != null) {
            lines.add(address);
        }
        if (c.contactPhone() != null) {
            lines.add("Tel: " + c.contactPhone());
        }
        if (c.contactEmail() != null) {
            lines.add("Email: " + c.contactEmail());
        }
        if (c.taxId() != null) {
            lines.add("TIN: " + c.taxId());
        }
        if (c.vrn() != null) {
            lines.add("VRN: " + c.vrn());
        }
        return lines;
    }

    /** Money to two places with thousands separators; blank when unknown. */
    public static String amount(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }

    /** A percentage to two places; blank when it cannot be computed. */
    public static String percent(BigDecimal v) {
        return v != null ? String.format("%,.2f%%", v) : "";
    }

    /** Whole quantities print without decimals; fractional ones (weighed goods) to three places. */
    public static String quantity(BigDecimal v) {
        if (v == null) {
            return "";
        }
        return v.stripTrailingZeros().scale() <= 0
                ? String.format("%,.0f", v)
                : String.format("%,.3f", v);
    }

    public static String text(String s) {
        return s != null ? s : "";
    }

    private static String joinAddress(ReportCompanyHeaderDto c) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{
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
