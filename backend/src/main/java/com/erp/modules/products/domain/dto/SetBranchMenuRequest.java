package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * One branch's entry for an item on the branch menu (ADR-0073): is it sold there, and at what price.
 * Every field but the branch is optional; a null leaves that part as it is.
 *
 * @param branchUid  the branch (must belong to the item's company)
 * @param soldHere   sold at this branch
 * @param price      the item's price at this branch, per base unit
 * @param clearPrice true = remove the branch price (the company price applies again)
 */
public record SetBranchMenuRequest(
        @NotBlank String branchUid,
        Boolean soldHere,
        @DecimalMin("0") BigDecimal price,
        Boolean clearPrice
) {
}
