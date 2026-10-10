package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.dto.GlPostingRetry;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.service.GlPostingRetryHandler;
import com.erp.modules.stock.service.InventoryGlPoster.CogsLeg;
import com.erp.modules.stock.service.InventoryGlPoster.ProjectCogsLeg;
import com.erp.modules.stock.service.InventoryGlPoster.ReceiptLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Re-posts the inventory GL legs whose automatic posting failed (ACC-02) by re-invoking the very
 * {@link InventoryGlPoster} method that failed, with the arguments it recorded. Not transactional
 * on purpose: each poster method runs in its own REQUIRES_NEW transaction (see
 * {@link GlPostingRetryHandler}).
 */
@Component
public class InventoryGlRetryHandler implements GlPostingRetryHandler {

    private static final Set<GlPostingFailureKind> KINDS = EnumSet.of(
            GlPostingFailureKind.STOCK_RECEIPT,
            GlPostingFailureKind.SALE_COGS,
            GlPostingFailureKind.PROJECT_COGS,
            GlPostingFailureKind.STOCK_RECEIPT_REVERSAL,
            GlPostingFailureKind.SALE_COGS_REVERSAL,
            GlPostingFailureKind.LANDED_COST,
            GlPostingFailureKind.PURCHASE_RETURN);

    private final InventoryGlPoster poster;
    private final ObjectMapper mapper;

    public InventoryGlRetryHandler(InventoryGlPoster poster, ObjectMapper mapper) {
        this.poster = poster;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(GlPostingFailureKind kind) {
        return KINDS.contains(kind);
    }

    @Override
    public String repost(GlPostingRetry r) {
        JsonNode a = r.args();
        String currency = text(a, "currency");
        return switch (r.kind()) {
            case STOCK_RECEIPT -> poster.postReceiptInNewTx(r.companyId(), r.branchId(),
                    r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    list(a, ReceiptLeg.class));
            case SALE_COGS -> poster.postCogsInNewTx(r.companyId(), r.branchId(),
                    r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    list(a, CogsLeg.class));
            case PROJECT_COGS -> poster.postCogsForProjectInNewTx(r.companyId(), r.branchId(),
                    r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    longValue(a, "projectId"), longValue(a, "projectTaskId"),
                    list(a, ProjectCogsLeg.class));
            case STOCK_RECEIPT_REVERSAL -> poster.postReceiptReversalInNewTx(r.companyId(),
                    r.branchId(), r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    decimal(a, "value"));
            case SALE_COGS_REVERSAL -> poster.postSaleReversalInNewTx(r.companyId(),
                    r.branchId(), r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    decimal(a, "value"));
            case LANDED_COST -> poster.postLandedCostInNewTx(r.companyId(), r.branchId(),
                    r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    decimal(a, "value"));
            case PURCHASE_RETURN -> poster.postPurchaseReturnInNewTx(r.companyId(), r.branchId(),
                    r.postingDate(), r.sourceRef(), r.documentNumber(), currency,
                    decimal(a, "value"));
            default -> throw new IllegalArgumentException(
                    "This posting cannot be re-posted from the stock ledger.");
        };
    }

    private <T> List<T> list(JsonNode args, Class<T> type) {
        JsonNode legs = args.path("legs");
        if (!legs.isArray()) {
            return List.of();
        }
        return mapper.convertValue(legs,
                mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    private static String text(JsonNode args, String key) {
        JsonNode n = args.path(key);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static Long longValue(JsonNode args, String key) {
        String s = text(args, key);
        return s == null || s.isBlank() ? null : Long.valueOf(s);
    }

    private static BigDecimal decimal(JsonNode args, String key) {
        JsonNode n = args.path(key);
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = text(args, key);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }
}
