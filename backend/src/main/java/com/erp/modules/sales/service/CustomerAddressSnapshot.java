package com.erp.modules.sales.service;

import com.erp.modules.parties.domain.entity.CustomerAddress;

/**
 * Formats a {@link CustomerAddress} into a single immutable snapshot string for the sales
 * ship-to / bill-to text columns (ADR-0041 D2). Pure / stateless.
 *
 * <p>Lines are joined with ", " skipping null/blank parts; format:
 * {@code line1, line2, city, region, postalCode, country}. Returns null when every part is blank.
 */
public final class CustomerAddressSnapshot {

    private CustomerAddressSnapshot() {
        // utility
    }

    public static String format(CustomerAddress a) {
        if (a == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        append(sb, a.getAddressLine1());
        append(sb, a.getAddressLine2());
        append(sb, a.getCity());
        append(sb, a.getRegion());
        append(sb, a.getPostalCode());
        append(sb, a.getCountry());
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim());
        }
    }
}
