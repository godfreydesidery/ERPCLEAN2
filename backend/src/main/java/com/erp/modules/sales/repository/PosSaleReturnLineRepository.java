package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto;
import com.erp.modules.sales.domain.entity.PosSaleReturnLine;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for the lines of POS item returns (ADR-0072). */
public interface PosSaleReturnLineRepository extends JpaRepository<PosSaleReturnLine, Long> {

    List<PosSaleReturnLine> findByCompanyIdAndPosSaleReturnIdOrderByLineNo(Long companyId,
                                                                           Long posSaleReturnId);

    /** Everything already returned against the given sold lines, one row per sold line. */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto(
                       l.salesInvoiceLineId,
                       SUM(l.qtyReturned), SUM(l.qtyReturnedBase),
                       SUM(l.netAmount), SUM(l.discountAmount), SUM(l.vatAmount), SUM(l.grossAmount))
            FROM PosSaleReturnLine l
            WHERE l.companyId = :companyId AND l.salesInvoiceLineId IN :lineIds
            GROUP BY l.salesInvoiceLineId
            """)
    List<PosReturnedLineTotalsDto> totalsByCompanyIdAndSoldLines(
            @Param("companyId") Long companyId, @Param("lineIds") Collection<Long> lineIds);
}
