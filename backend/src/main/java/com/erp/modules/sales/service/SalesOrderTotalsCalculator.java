package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.QuotationLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.platform.common.money.CurrencyMinorUnits;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Totals algorithm for Quotation and SalesOrder headers + lines (ADR-0021 D-9).
 *
 * <p>Reuses the IDENTICAL algorithm as {@link InvoiceTotalsCalculator} (BR-SO-10, OQ-SO-06,
 * VAT-inclusive branch ADR-0056 D-5): per-line raw amount after line discount, doc-discount
 * apportioned pro-rata, then branch on the line's {@code priceInclusive} snapshot — EXCLUSIVE
 * lines add VAT on top of net (unchanged); INCLUSIVE lines treat the raw amount as GROSS and strip
 * VAT out (gross-preserving: {@code net + vat = gross} exactly). HALF_UP at each boundary. Ensures
 * SO ↔ invoice agreement to the cent (NFR-SO-03).
 *
 * <p>Rounds to the minor units of the document's own currency ({@link CurrencyMinorUnits}) —
 * exactly as {@link InvoiceTotalsCalculator} does, so an order and the invoice billed from it agree
 * in USD cents as well as in whole shillings.
 */
@Component
public class SalesOrderTotalsCalculator {

    private static final RoundingMode MODE = RoundingMode.HALF_UP;

    private final CurrencyMinorUnits minorUnits;

    @Autowired
    public SalesOrderTotalsCalculator(CurrencyMinorUnits minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Database-free construction (unit tests): minor units from {@link CurrencyMinorUnits#FALLBACK}. */
    public SalesOrderTotalsCalculator() {
        this(CurrencyMinorUnits.FALLBACK);
    }

    // -------------------------------------------------------------------------
    // SalesOrder overload
    // -------------------------------------------------------------------------

    public void recompute(SalesOrder order, List<SalesOrderLine> lines) {
        if (lines.isEmpty()) {
            order.setNetTotalAmount(BigDecimal.ZERO);
            order.setVatTotalAmount(BigDecimal.ZERO);
            order.setGrossTotalAmount(BigDecimal.ZERO);
            return;
        }
        List<LineData> data = lines.stream().map(l -> new LineData(
                l.getUnitPriceAmount(), l.getQtyOrdered(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(),
                l.getVatRate() != null ? l.getVatRate() : BigDecimal.ZERO,
                l.isPriceInclusive()
        )).toList();

        Totals totals = compute(data,
                order.getDocDiscountAmount(), order.getDocDiscountPercent(),
                minorUnits.of(order.getCurrency()));

        // Push computed values back onto line entities
        for (int i = 0; i < lines.size(); i++) {
            SalesOrderLine line = lines.get(i);
            LineResult r = totals.lineResults.get(i);
            line.setNetAmount(r.net);
            line.setVatAmount(r.vat);
            line.setGrossAmount(r.net.add(r.vat));
        }
        order.setNetTotalAmount(totals.netTotal);
        order.setVatTotalAmount(totals.vatTotal);
        order.setGrossTotalAmount(totals.netTotal.add(totals.vatTotal));
    }

    // -------------------------------------------------------------------------
    // Quotation overload
    // -------------------------------------------------------------------------

    public void recompute(Quotation quote, List<QuotationLine> lines) {
        if (lines.isEmpty()) {
            quote.setNetTotalAmount(BigDecimal.ZERO);
            quote.setVatTotalAmount(BigDecimal.ZERO);
            quote.setGrossTotalAmount(BigDecimal.ZERO);
            return;
        }
        List<LineData> data = lines.stream().map(l -> new LineData(
                l.getUnitPriceAmount(), l.getQuantity(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(),
                l.getVatRate() != null ? l.getVatRate() : BigDecimal.ZERO,
                l.isPriceInclusive()
        )).toList();

        Totals totals = compute(data,
                quote.getDocDiscountAmount(), quote.getDocDiscountPercent(),
                minorUnits.of(quote.getCurrency()));

        for (int i = 0; i < lines.size(); i++) {
            QuotationLine line = lines.get(i);
            LineResult r = totals.lineResults.get(i);
            line.setNetAmount(r.net);
            line.setVatAmount(r.vat);
            line.setGrossAmount(r.net.add(r.vat));
        }
        quote.setNetTotalAmount(totals.netTotal);
        quote.setVatTotalAmount(totals.vatTotal);
        quote.setGrossTotalAmount(totals.netTotal.add(totals.vatTotal));
    }

    // -------------------------------------------------------------------------
    // Core algorithm — identical to InvoiceTotalsCalculator math (D-9 / OQ-SO-06)
    // -------------------------------------------------------------------------

    private Totals compute(List<LineData> lines,
                           BigDecimal docDiscountAmount, BigDecimal docDiscountPercent,
                           int scale) {
        // Step 1: raw amount per line (NET for an exclusive line, GROSS for an inclusive one —
        // ADR-0056 D-5; the math is identical, only the step-3 derivation differs).
        List<BigDecimal> rawNets = new ArrayList<>(lines.size());
        for (LineData l : lines) {
            BigDecimal gross = l.unitPrice.multiply(l.qty);
            BigDecimal lineDis = resolveLineDiscount(l, gross, scale);
            rawNets.add(gross.subtract(lineDis).max(BigDecimal.ZERO).setScale(scale, MODE));
        }

        // Step 2: apportion doc discount
        BigDecimal docDiscount = resolveDocDiscount(rawNets, docDiscountAmount, docDiscountPercent, scale);
        BigDecimal sumRaw = rawNets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BigDecimal> discountedNets = new ArrayList<>(lines.size());
        if (sumRaw.compareTo(BigDecimal.ZERO) == 0 || docDiscount.compareTo(BigDecimal.ZERO) == 0) {
            discountedNets.addAll(rawNets);
        } else {
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < lines.size(); i++) {
                BigDecimal share;
                if (i == lines.size() - 1) {
                    share = docDiscount.subtract(allocated);
                } else {
                    share = docDiscount.multiply(rawNets.get(i))
                            .divide(sumRaw, scale + 4, MODE)
                            .setScale(scale, MODE);
                    allocated = allocated.add(share);
                }
                discountedNets.add(rawNets.get(i).subtract(share).max(BigDecimal.ZERO).setScale(scale, MODE));
            }
        }

        // Step 3: derive net/vat per line, branching on the line's own priceInclusive (ADR-0056 D-5)
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        List<LineResult> results = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            LineData l = lines.get(i);
            BigDecimal discountedRaw = discountedNets.get(i);
            BigDecimal net;
            BigDecimal vat;
            if (l.priceInclusive) {
                // discountedRaw is GROSS — strip VAT out so net + vat reproduces it exactly.
                net = stripVat(discountedRaw, l.vatRate, scale);
                vat = discountedRaw.subtract(net);
            } else {
                // Unchanged pre-ADR-0056 behaviour: discountedRaw is NET, VAT added on top.
                net = discountedRaw;
                vat = net.multiply(l.vatRate).setScale(scale, MODE);
            }
            results.add(new LineResult(net, vat));
            netTotal = netTotal.add(net);
            vatTotal = vatTotal.add(vat);
        }
        return new Totals(results, netTotal, vatTotal);
    }

    /**
     * ADR-0056 D-5: strips VAT out of a GROSS amount — {@code net = round(gross / (1 + rate))}.
     * {@code rate = 0} (ZERO_RATED/EXEMPT) is the identity case: {@code net = gross}, no division
     * anomaly. The caller derives {@code vat = gross − net} so {@code net + vat = gross} exactly.
     */
    private static BigDecimal stripVat(BigDecimal gross, BigDecimal vatRate, int scale) {
        return gross.divide(BigDecimal.ONE.add(vatRate), scale, MODE);
    }

    private BigDecimal resolveLineDiscount(LineData l, BigDecimal gross, int scale) {
        if (l.discountAmount != null && l.discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            return l.discountAmount.setScale(scale, MODE);
        }
        if (l.discountPercent != null && l.discountPercent.compareTo(BigDecimal.ZERO) > 0) {
            return gross.multiply(l.discountPercent).divide(BigDecimal.valueOf(100), scale, MODE);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveDocDiscount(List<BigDecimal> rawNets,
                                          BigDecimal amount, BigDecimal percent, int scale) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            return amount.setScale(scale, MODE);
        }
        if (percent != null && percent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal sum = rawNets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.multiply(percent).divide(BigDecimal.valueOf(100), scale, MODE);
        }
        return BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // Value types
    // -------------------------------------------------------------------------

    private record LineData(BigDecimal unitPrice, BigDecimal qty,
                            BigDecimal discountAmount, BigDecimal discountPercent,
                            BigDecimal vatRate, boolean priceInclusive) {}

    private record LineResult(BigDecimal net, BigDecimal vat) {}

    private record Totals(List<LineResult> lineResults,
                          BigDecimal netTotal, BigDecimal vatTotal) {}
}
