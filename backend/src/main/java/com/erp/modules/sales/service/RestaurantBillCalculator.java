package com.erp.modules.sales.service;

import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Prices a restaurant order exactly as the POS sale will (ADR-0065 D-7, D-8).
 *
 * <p>The bill must show the figure payment charges, so it is not computed by a second formula. It
 * runs the invoice's own pieces: {@link LinePriceResolver} for each line's price (the customer's
 * contract price, else their list, else the company default) and {@link InvoiceTotalsCalculator}
 * for the VAT and rounding, over throw-away {@link SalesInvoice}/{@link SalesInvoiceLine} objects
 * that are never persisted. Payment still compares the posted invoice with this figure and refuses
 * on any difference, so a drift here can only ever stop a sale, never overcharge one.
 */
@Component
class RestaurantBillCalculator {

    /** One line to price: a product in a unit, already combined across rounds. */
    record Item(Long productId, Long unitId, BigDecimal quantity) {}

    /** A priced line; {@code unitGross} and {@code gross} are VAT-inclusive. */
    record PricedLine(Long productId, Long unitId, String productName, String unitName,
                      BigDecimal quantity, BigDecimal unitGross, BigDecimal net, BigDecimal vat,
                      BigDecimal gross) {}

    record Priced(List<PricedLine> lines, BigDecimal net, BigDecimal vat, BigDecimal gross) {}

    private final PriceResolutionService prices;
    private final InvoiceTotalsCalculator totals;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final CustomerRepository customers;
    private final TaxRateRepository taxRates;
    private final CompanyCalendar calendar;

    RestaurantBillCalculator(PriceResolutionService prices, InvoiceTotalsCalculator totals,
                             ProductRepository products, UnitOfMeasureRepository units,
                             CustomerRepository customers, TaxRateRepository taxRates,
                             CompanyCalendar calendar) {
        this.prices    = prices;
        this.totals    = totals;
        this.products  = products;
        this.units     = units;
        this.customers = customers;
        this.taxRates  = taxRates;
        this.calendar  = calendar;
    }

    /**
     * @param customerId who the sale is for (loaded company-scoped; null prices as a walk-in)
     * @param currency   the sale's currency code
     */
    Priced price(Long companyId, Long branchId, Long customerId, String currency, List<Item> items) {
        var invoice = new SalesInvoice(companyId, branchId, customerId, null, currency, null);
        var customer = LinePriceResolver.pricingCustomer(customers, companyId, customerId);
        var businessDate = calendar.today(companyId);

        List<SalesInvoiceLine> lines = new ArrayList<>(items.size());
        List<String> productNames = new ArrayList<>(items.size());
        short lineNo = 0;
        for (Item item : items) {
            Product product = products.findByCompanyIdAndId(companyId, item.productId())
                    .orElseThrow(() -> new NotFoundException("This menu item no longer exists."));
            UnitOfMeasure unit = units.findByCompanyIdAndId(companyId, item.unitId())
                    .orElseThrow(() -> new NotFoundException("This unit no longer exists."));
            UnitListPriceDto price = LinePriceResolver.resolve(prices,
                    LinePriceResolver.query(companyId, product.getId(), unit.getId(), customer,
                            currency, item.quantity(), businessDate),
                    null);
            BigDecimal vatRate = taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                    .map(TaxRate::getRate)
                    .orElseThrow(() -> new IllegalStateException(
                            "VAT rate not configured for status " + product.getVatStatus() + "."));
            var line = new SalesInvoiceLine(invoice, ++lineNo,
                    product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(),
                    item.quantity(), item.quantity(),
                    price.amount(), price.amount(),
                    product.getVatStatus(), vatRate, null);
            line.setPriceInclusive(price.vatInclusive());
            lines.add(line);
            productNames.add(product.getName());
        }

        totals.recompute(invoice, lines);

        List<PricedLine> priced = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            SalesInvoiceLine l = lines.get(i);
            BigDecimal unitGross = l.getQuantity().signum() == 0
                    ? BigDecimal.ZERO
                    : l.getGrossAmount().divide(l.getQuantity(),
                            l.getGrossAmount().scale(), RoundingMode.HALF_UP);
            priced.add(new PricedLine(l.getProductId(), l.getUnitId(), productNames.get(i),
                    l.getUnitName(), l.getQuantity(), unitGross,
                    l.getNetAmount(), l.getVatAmount(), l.getGrossAmount()));
        }
        return new Priced(priced, invoice.getNetTotalAmount(), invoice.getVatTotalAmount(),
                invoice.getGrossTotalAmount());
    }
}
