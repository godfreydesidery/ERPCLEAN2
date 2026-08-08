package com.erp.modules.stock.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO: create a stock transfer (FR-INVD-08, ADR-0028 D-5).
 *
 * <p><strong>Transfer modes.</strong> Both modes work between ANY two locations in the company,
 * whether or not they sit in the same branch — the only rule is that source and destination must
 * differ. The choice is about who confirms arrival, not about how far the stock travels:
 * <ul>
 *   <li>{@code INSTANT} — one-sided. The sender posts both legs in a single transaction
 *       (DRAFT → COMPLETED); nobody at the destination has to confirm anything, and the destination
 *       branch does not need a single user account for it to work. This is the mode for moving
 *       stock to a shop that is not on the system.</li>
 *   <li>{@code IN_TRANSIT} — two-sided. Stock parks in the in-transit location on dispatch and
 *       someone at the destination confirms receipt (DRAFT → DISPATCHED → RECEIVED). Use it when
 *       the receiving side is on the system and you want the goods-in-transit visibility.</li>
 * </ul>
 * Defaults to {@code IN_TRANSIT} when omitted.
 *
 * @param sourceLocationUid location the stock leaves
 * @param destLocationUid   location the stock arrives at (must differ from the source; may sit in
 *                          another branch)
 * @param transferDate      business date of the transfer
 * @param transferMode      {@code INSTANT} or {@code IN_TRANSIT} — see above
 * @param notes             optional free-text
 * @param lines             products and quantities to move (at least one)
 */
public record CreateStockTransferRequest(
        @NotBlank String sourceLocationUid,
        @NotBlank String destLocationUid,
        @NotNull LocalDate transferDate,
        @NotBlank String transferMode,
        @Size(max = 500) String notes,
        @NotEmpty @Valid List<LineRequest> lines
) {
    /** A single line item: product + quantity. */
    public record LineRequest(
            @NotBlank String productUid,
            @NotNull @Positive BigDecimal qty
    ) {}
}
