package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Totals algorithm (ADR-0008 D-4, NFR-SALES-02, brief §5; VAT-inclusive branch ADR-0056 D-5).
 *
 * <p>Algorithm (authoritative — web must mirror exactly). Steps 1-2 are IDENTICAL regardless of a
 * line's {@code priceInclusive} snapshot — only the meaning of the raw per-line amount and the
 * step-3 derivation differ:
 * <ol>
 *   <li>Per line: {@code raw = round(unitPrice × qty) − lineDiscount} (discount resolved from
 *       amount or percent) — for an EXCLUSIVE line this is a raw NET; for an INCLUSIVE line
 *       (ADR-0056) {@code unitPrice} is itself a GROSS amount, so {@code raw} is a raw GROSS.</li>
 *   <li>Apportion the document discount pro-rata across lines by each line's {@code raw};
 *       recompute each line's discounted {@code raw}.</li>
 *   <li>Per line, branch on {@code priceInclusive}:
 *     <ul>
 *       <li>EXCLUSIVE (false, unchanged from pre-ADR-0056): {@code net = discountedRaw};
 *           {@code vat = round(net × vatRate)} (0 for ZERO_RATED/EXEMPT);
 *           {@code gross = net + vat}.</li>
 *       <li>INCLUSIVE (true, ADR-0056 — gross-preserving/exact): {@code gross = discountedRaw};
 *           {@code net = round(gross / (1 + vatRate))}; {@code vat = gross − net}. By
 *           construction {@code net + vat = gross} EXACTLY — the entered inclusive price is
 *           reproduced to the shilling, never off by a rounding cent.</li>
 *     </ul>
 *   </li>
 *   <li>Header: {@code net_total = Σ net}; {@code vat_total = Σ vat};
 *       {@code gross_total = net_total + vat_total}.</li>
 *   <li>{@code tax_summary} = group lines by (vat_status, vat_rate), summing the DERIVED net+vat
 *       per band (correct regardless of per-line stance mix).</li>
 * </ol>
 *
 * <p>Rounding: HALF_UP at each boundary (per line, per band). TZS = 0 dp in practice;
 * storage is NUMERIC(19,4) — we round to 0 dp here per OQ-CUR-03 recommended default.
 */
@Component
public class InvoiceTotalsCalculator {

    /** Rounding scale. OQ-CUR-03: TZS = 0 decimal places in practice. Storage is (19,4). */
    private static final int SCALE = 0;
    private static final RoundingMode MODE = RoundingMode.HALF_UP;

    private final ObjectMapper objectMapper;

    public InvoiceTotalsCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Recomputes all line and header totals, updates the entities in-place, and writes
     * {@code tax_summary} JSON onto the header. Called on every DRAFT mutation and at finalise.
     *
     * @param invoice the header to update
     * @param lines   the current set of lines (may be empty → all zeros)
     */
    public void recompute(SalesInvoice invoice, List<SalesInvoiceLine> lines) {
        if (lines.isEmpty()) {
            invoice.setNetTotalAmount(BigDecimal.ZERO);
            invoice.setVatTotalAmount(BigDecimal.ZERO);
            invoice.setGrossTotalAmount(BigDecimal.ZERO);
            invoice.setTaxSummary(null);
            return;
        }

        // --- Step 1: per-line raw net (before doc discount) ---
        List<BigDecimal> rawNets = new ArrayList<>(lines.size());
        for (SalesInvoiceLine line : lines) {
            BigDecimal gross = line.getUnitPriceAmount().multiply(line.getQuantity());
            BigDecimal lineDis = resolveLineDiscount(line);
            BigDecimal rawNet = gross.subtract(lineDis).max(BigDecimal.ZERO);
            rawNet = rawNet.setScale(SCALE, MODE);
            rawNets.add(rawNet);
        }

        // --- Step 2: apportion doc discount pro-rata across lines ---
        BigDecimal docDiscount = resolveDocDiscount(invoice, rawNets);
        BigDecimal sumRaw = rawNets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BigDecimal> discountedNets = new ArrayList<>(lines.size());
        if (sumRaw.compareTo(BigDecimal.ZERO) == 0 || docDiscount.compareTo(BigDecimal.ZERO) == 0) {
            discountedNets.addAll(rawNets);
        } else {
            // Apportion pro-rata; last line absorbs any penny rounding residual
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < lines.size(); i++) {
                BigDecimal share;
                if (i == lines.size() - 1) {
                    // last line: remainder to avoid rounding gap
                    share = docDiscount.subtract(allocated);
                } else {
                    share = docDiscount.multiply(rawNets.get(i))
                            .divide(sumRaw, SCALE + 4, MODE)   // extra precision then round
                            .setScale(SCALE, MODE);
                    allocated = allocated.add(share);
                }
                BigDecimal dNet = rawNets.get(i).subtract(share).max(BigDecimal.ZERO);
                discountedNets.add(dNet.setScale(SCALE, MODE));
            }
        }

        // --- Step 3: per-line VAT and gross; update line entities ---
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;

        // Key: "status|rate" → [net, vat]
        Map<String, BigDecimal[]> bands = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            SalesInvoiceLine line = lines.get(i);
            BigDecimal discountedRaw = discountedNets.get(i);
            BigDecimal lineNet;
            BigDecimal vat;
            BigDecimal lineGross;
            if (line.isPriceInclusive()) {
                // ADR-0056 D-5: discountedRaw is GROSS — strip VAT out so net + vat reproduces it
                // exactly (gross-preserving).
                lineGross = discountedRaw;
                lineNet = stripVat(lineGross, line.getVatRate());
                vat = lineGross.subtract(lineNet);
            } else {
                // Unchanged pre-ADR-0056 behaviour: discountedRaw is NET, VAT added on top.
                lineNet = discountedRaw;
                vat = lineNet.multiply(line.getVatRate()).setScale(SCALE, MODE);
                lineGross = lineNet.add(vat);
            }

            line.setNetAmount(lineNet);
            line.setVatAmount(vat);
            line.setGrossAmount(lineGross);

            netTotal = netTotal.add(lineNet);
            vatTotal = vatTotal.add(vat);

            String bandKey = line.getVatStatus().name() + "|" + line.getVatRate().toPlainString();
            BigDecimal[] band = bands.computeIfAbsent(bandKey, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            band[0] = band[0].add(lineNet);
            band[1] = band[1].add(vat);
        }

        // --- Step 4: header totals ---
        invoice.setNetTotalAmount(netTotal);
        invoice.setVatTotalAmount(vatTotal);
        invoice.setGrossTotalAmount(netTotal.add(vatTotal));

        // --- Step 5: tax_summary JSONB ---
        invoice.setTaxSummary(buildTaxSummary(bands));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * ADR-0056 D-5: strips VAT out of a GROSS amount — {@code net = round(gross / (1 + rate))}.
     * {@code rate = 0} (ZERO_RATED/EXEMPT) is the identity case: {@code net = gross}, no division
     * anomaly. The caller derives {@code vat = gross − net} so {@code net + vat = gross} exactly.
     */
    private static BigDecimal stripVat(BigDecimal gross, BigDecimal vatRate) {
        return gross.divide(BigDecimal.ONE.add(vatRate), SCALE, MODE);
    }

    private BigDecimal resolveLineDiscount(SalesInvoiceLine line) {
        // Amount takes precedence; fall back to percent × list-net
        if (line.getLineDiscountAmount() != null
                && line.getLineDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return line.getLineDiscountAmount().setScale(SCALE, MODE);
        }
        if (line.getLineDiscountPercent() != null
                && line.getLineDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal lineGross = line.getUnitPriceAmount().multiply(line.getQuantity());
            return lineGross.multiply(line.getLineDiscountPercent())
                    .divide(BigDecimal.valueOf(100), SCALE, MODE);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveDocDiscount(SalesInvoice invoice, List<BigDecimal> rawNets) {
        if (invoice.getDocDiscountAmount() != null
                && invoice.getDocDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            return invoice.getDocDiscountAmount().setScale(SCALE, MODE);
        }
        if (invoice.getDocDiscountPercent() != null
                && invoice.getDocDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal sumRaw = rawNets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return sumRaw.multiply(invoice.getDocDiscountPercent())
                    .divide(BigDecimal.valueOf(100), SCALE, MODE);
        }
        return BigDecimal.ZERO;
    }

    private String buildTaxSummary(Map<String, BigDecimal[]> bands) {
        // Produce JSON array: [{"status":"STANDARD","rate":"0.1800","net":"...","vat":"..."}]
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> entry : bands.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            BigDecimal[] totals = entry.getValue();
            rows.add(Map.of(
                    "status", parts[0],
                    "rate",   parts[1],
                    "net",    totals[0].setScale(4, MODE).toPlainString(),
                    "vat",    totals[1].setScale(4, MODE).toPlainString()
            ));
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise tax_summary", e);
        }
    }
}
