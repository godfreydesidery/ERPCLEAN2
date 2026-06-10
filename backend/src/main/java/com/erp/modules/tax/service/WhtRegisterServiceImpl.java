package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtRegisterRowDto;
import com.erp.modules.tax.domain.entity.WhtTransaction;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTransactionRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    public WhtRegisterServiceImpl(WhtTransactionRepository whtTransactions,
                                   ScopeGuard scopeGuard) {
        this.whtTransactions = whtTransactions;
        this.scopeGuard      = scopeGuard;
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

        List<WhtRegisterRowDto> payableDtos = payableRows.stream()
                .map(this::toRow)
                .toList();
        List<WhtRegisterRowDto> receivableDtos = receivableRows.stream()
                .map(this::toRow)
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

    private WhtRegisterRowDto toRow(WhtTransaction t) {
        return new WhtRegisterRowDto(
                t.getWhtNumber(),
                t.getKind(),
                t.getPartyKind(),
                t.getPartyName(),
                t.getSourceRef(),
                t.getTaxableBase(),
                t.getWhtAmount(),
                t.getCertificateDate());
    }
}
