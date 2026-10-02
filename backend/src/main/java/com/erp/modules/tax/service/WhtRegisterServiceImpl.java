package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtRegisterRowDto;
import com.erp.modules.tax.domain.entity.WhtTransaction;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTransactionRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WHT period register (ADR-0017 D-9, FR-WHT-04).
 */
@Service
@Transactional(readOnly = true)
public class WhtRegisterServiceImpl implements WhtRegisterService {

    private final WhtTransactionRepository whtTransactions;
    private final ScopeGuard              scopeGuard;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The name AP payments stamped on every WHT certificate before 2026-10 instead of the
     * supplier's — see {@link #supplierNames}.
     */
    static final String PLACEHOLDER_SUPPLIER_NAME = "Supplier";

    public WhtRegisterServiceImpl(WhtTransactionRepository whtTransactions,
                                   ScopeGuard scopeGuard,
                                   JdbcTemplate jdbc) {
        this.whtTransactions = whtTransactions;
        this.scopeGuard      = scopeGuard;
        this.jdbc            = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public WhtRegisterDto getRegister(Long companyId, LocalDate periodStart, LocalDate periodEnd) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        List<WhtTransaction> payableRows = whtTransactions
                .findByCompanyIdAndKindAndCertificateDateBetween(
                        companyId, WhtKind.WHT_ON_PAYMENT, periodStart, periodEnd);

        List<WhtTransaction> receivableRows = whtTransactions
                .findByCompanyIdAndKindAndCertificateDateBetween(
                        companyId, WhtKind.WHT_ON_RECEIPT, periodStart, periodEnd);

        Map<Long, String> names = supplierNames(companyId, payableRows);
        List<WhtRegisterRowDto> payableDtos = payableRows.stream()
                .map(t -> toRow(t, names))
                .toList();
        List<WhtRegisterRowDto> receivableDtos = receivableRows.stream()
                .map(t -> toRow(t, Map.of()))
                .toList();

        BigDecimal totalPayable = payableRows.stream()
                .map(WhtTransaction::getWhtAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReceivable = receivableRows.stream()
                .map(WhtTransaction::getWhtAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WhtRegisterDto(companyId, periodStart, periodEnd,
                payableDtos, totalPayable,
                receivableDtos, totalReceivable);
    }

    @Override
    @Transactional
    public void markRemitted(String whtTransactionUid, String remittancePeriod, String remittanceRef) {
        WhtTransaction txn = whtTransactions.findByUid(whtTransactionUid)
                .orElseThrow(() -> new NotFoundException("WHT transaction not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), txn.getCompanyId());
        if (txn.isRemitted()) {
            // WHT transaction uid not exposed in the message
            throw new ConflictException(
                    "This WHT transaction has already been marked as remitted.");
        }
        txn.setRemitted(true);
        txn.setRemittancePeriod(remittancePeriod);
        txn.setRemittanceRef(remittanceRef);
        txn.setRemittedAt(Instant.now());
        txn.setRemittedBy(actorId());
        whtTransactions.save(txn);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    /**
     * Live supplier names for the certificates that were stamped with the literal placeholder
     * "Supplier" (every AP-payment WHT row before 2026-10). The certificate keeps its stored name
     * otherwise — it is a snapshot of who was certified — but a placeholder names nobody, so the
     * register reads the supplier it points at ({@code party_id}, company-scoped) instead. That
     * fixes the history on screen and in the export without a data repair. Scalar native SQL: the
     * tax module never imports a parties entity.
     */
    private Map<Long, String> supplierNames(Long companyId, List<WhtTransaction> rows) {
        Set<Long> ids = rows.stream()
                .filter(WhtRegisterServiceImpl::hasPlaceholderName)
                .map(WhtTransaction::getPartyId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        jdbc.query("""
                SELECT id, display_name FROM suppliers
                WHERE company_id = :companyId AND id IN (:ids)
                """,
                new MapSqlParameterSource("companyId", companyId).addValue("ids", ids),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("display_name"));
                });
        return names;
    }

    private static boolean hasPlaceholderName(WhtTransaction t) {
        return "SUPPLIER".equals(t.getPartyKind())
                && PLACEHOLDER_SUPPLIER_NAME.equals(t.getPartyName());
    }

    private WhtRegisterRowDto toRow(WhtTransaction t, Map<Long, String> liveSupplierNames) {
        String name = t.getPartyName();
        if (hasPlaceholderName(t)) {
            String live = liveSupplierNames.get(t.getPartyId());
            if (live != null && !live.isBlank()) {
                name = live;
            }
        }
        return new WhtRegisterRowDto(
                t.getWhtNumber(),
                t.getKind(),
                t.getPartyKind(),
                name,
                t.getSourceRef(),
                t.getTaxableBase(),
                t.getWhtAmount(),
                t.getCertificateDate());
    }
}
