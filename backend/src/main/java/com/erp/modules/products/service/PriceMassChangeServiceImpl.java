package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.MassPriceChangeRequest;
import com.erp.modules.products.domain.dto.MassPriceChangeResult;
import com.erp.modules.products.domain.dto.MassPriceChangeResult.Sample;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.enums.MassPriceChangeType;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.Money;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a rule-based mass price change to every price on a list (ADR: bulk data operations).
 * Prices are overwritten in place (the current model has no price-history versioning); a
 * {@code dryRun} previews the effect without writing. Scope is asserted from the LOADED price list's
 * company — never a caller-supplied id (confused-deputy rule). One transaction: all-or-nothing.
 */
@Service
@Transactional
public class PriceMassChangeServiceImpl implements PriceMassChangeService {

    /** Cap on the before/after samples returned for the preview. */
    private static final int MAX_SAMPLES = 25;
    private static final int DEFAULT_SCALE = 2;

    private final PriceListRepository priceLists;
    private final ProductPriceRepository prices;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public PriceMassChangeServiceImpl(PriceListRepository priceLists,
                                      ProductPriceRepository prices,
                                      ScopeGuard scopeGuard,
                                      AuditService audit) {
        this.priceLists = priceLists;
        this.prices = prices;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public MassPriceChangeResult apply(MassPriceChangeRequest req) {
        PriceList priceList = priceLists.findByUid(req.priceListUid())
                .orElseThrow(() -> new NotFoundException("Price list not found."));
        // Scope from the loaded entity's company (not a caller param).
        scopeGuard.assertCanActIn(RequestContext.get(), priceList.getCompanyId());

        int scale = req.roundToDecimals() != null ? req.roundToDecimals() : DEFAULT_SCALE;
        List<ProductPrice> rows =
                prices.findByCompanyIdAndPriceListId(priceList.getCompanyId(), priceList.getId());

        List<Sample> samples = new ArrayList<>();
        int affected = 0;
        for (ProductPrice pp : rows) {
            Money current = pp.getPrice();
            if (current == null || !current.isPresent()) {
                continue;
            }
            BigDecimal oldAmount = current.getAmount();
            BigDecimal newAmount = compute(req.type(), req.value(), oldAmount)
                    .setScale(scale, RoundingMode.HALF_UP);
            if (newAmount.signum() < 0) {
                newAmount = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
            }
            if (newAmount.compareTo(oldAmount) == 0) {
                continue;
            }
            affected++;
            if (samples.size() < MAX_SAMPLES) {
                samples.add(new Sample(pp.getProduct().getCode(), oldAmount, newAmount,
                        current.getCurrency().value()));
            }
            if (!req.dryRun()) {
                pp.setPrice(new Money(newAmount, current.getCurrency().value()));
                pp.setUpdatedAt(Instant.now());
                pp.setUpdatedBy(actorId());
            }
        }

        if (!req.dryRun() && affected > 0) {
            audit.record(AuditEvent.of(AuditActions.PRICE_MASS_CHANGE, "price_lists",
                            priceList.getId(), priceList.getUid())
                    .detail(Map.of("type", req.type().name(),
                            "value", req.value().toPlainString(),
                            "affected", String.valueOf(affected))));
        }

        return new MassPriceChangeResult(priceList.getUid(), priceList.getCode(), priceList.getName(),
                rows.size(), affected, req.dryRun(), samples);
    }

    private static BigDecimal compute(MassPriceChangeType type, BigDecimal value, BigDecimal old) {
        return switch (type) {
            case PERCENT -> old.add(old.multiply(value).divide(BigDecimal.valueOf(100)));
            case AMOUNT -> old.add(value);
            case SET -> value;
        };
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
