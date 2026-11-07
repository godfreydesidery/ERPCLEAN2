package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.OfflinePeriodReason;
import com.erp.modules.sales.domain.enums.TenderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What a till uploads when it reaches the server after selling offline (ADR-0068 D-5). One batch
 * per call: the shifts it opened offline, the sales it rang, and its offline periods. Every item
 * carries the till's own key, so sending the same batch twice changes nothing.
 */
public record OfflineSyncRequest(
        @NotBlank String tillUid,
        @Valid @Size(max = 20) List<OfflineSession> sessions,
        @Valid @Size(max = 200) List<OfflineSale> sales,
        @Valid @Size(max = 50) List<OfflinePeriod> periods
) {

    /** A shift the till opened while offline (D-4). */
    public record OfflineSession(
            @NotBlank @Size(max = 64) String clientKey,
            @NotNull Instant openedAt,
            @NotNull @PositiveOrZero BigDecimal openingFloatAmount
    ) {}

    /**
     * One offline sale exactly as the till rang it. It belongs to an existing shift
     * ({@code sessionUid}) or to a shift opened offline in this batch or an earlier one
     * ({@code sessionClientKey}) — exactly one of the two.
     */
    public record OfflineSale(
            @NotBlank @Size(max = 80) String idempotencyKey,
            @NotBlank @Size(max = 40) String offlineNumber,
            @NotNull Instant soldAt,
            @Size(max = 26) String sessionUid,
            @Size(max = 64) String sessionClientKey,
            @NotNull Long customerId,
            @NotBlank String currency,
            @NotEmpty @Valid List<Line> lines,
            @NotEmpty @Valid List<Tender> tenders,
            /** The total the till showed the customer; compared, never trusted, at sync. */
            @NotNull BigDecimal grossTotal,
            Boolean ageVerified,
            @Size(max = 500) String notes,
            /**
             * Who rang the sale on the till (offline sign-in, D-7). Recorded in the audit trail; the
             * invoice itself is created by the user who syncs. Optional — absent on older tills.
             */
            @Size(max = 26) String rungByUid
    ) {
        /** Back-compat: a till that does not send who rang the sale. */
        public OfflineSale(String idempotencyKey, String offlineNumber, Instant soldAt,
                           String sessionUid, String sessionClientKey, Long customerId,
                           String currency, List<Line> lines, List<Tender> tenders,
                           BigDecimal grossTotal, Boolean ageVerified, String notes) {
            this(idempotencyKey, offlineNumber, soldAt, sessionUid, sessionClientKey, customerId,
                    currency, lines, tenders, grossTotal, ageVerified, notes, null);
        }
    }

    /** A line at the price the till charged and that price's VAT stance. */
    public record Line(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @NotNull @DecimalMin("0.0001") BigDecimal unitPrice,
            @NotNull Boolean priceInclusive
    ) {}

    /** Cash, mobile money or card only (D-3); mobile money and card need their reference. */
    public record Tender(
            @NotNull TenderType tenderType,
            @NotNull @DecimalMin("0.0001") BigDecimal amount,
            @Size(max = 80) String reference
    ) {}

    /** A stretch of time the till sold offline (D-1, D-8). */
    public record OfflinePeriod(
            @NotBlank @Size(max = 64) String clientKey,
            @NotNull OfflinePeriodReason reason,
            /** Who switched: the manager for MANUAL; absent means the signed-in user (UNREACHABLE). */
            @Size(max = 26) String switchedByUid,
            @NotNull Instant startedAt,
            Instant endedAt,
            @PositiveOrZero int salesCount,
            @Size(max = 26) String sessionUid,
            @Size(max = 64) String sessionClientKey
    ) {}
}
