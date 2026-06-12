package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/** Ordered marginal PAYE band (ADR-0032 D-3). Immutable once created. */
@Getter
@Entity
@Table(name = "paye_bands")
public class PayeBand extends UidEntity {

    @Column(name = "paye_band_set_id", nullable = false, updatable = false)
    private Long payeBandSetId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "band_no", nullable = false, updatable = false)
    private Short bandNo;

    @Column(name = "lower_bound", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lowerBound;

    @Column(name = "marginal_rate", nullable = false, precision = 9, scale = 4, updatable = false)
    private BigDecimal marginalRate;

    @Column(name = "cumulative_fixed_tax", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal cumulativeFixedTax;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected PayeBand() {}

    public PayeBand(Long payeBandSetId, Long companyId, short bandNo,
                    BigDecimal lowerBound, BigDecimal marginalRate,
                    BigDecimal cumulativeFixedTax, Long createdBy) {
        this.payeBandSetId     = payeBandSetId;
        this.companyId         = companyId;
        this.bandNo            = bandNo;
        this.lowerBound        = lowerBound;
        this.marginalRate      = marginalRate;
        this.cumulativeFixedTax = cumulativeFixedTax;
        this.createdBy         = createdBy;
    }
}
