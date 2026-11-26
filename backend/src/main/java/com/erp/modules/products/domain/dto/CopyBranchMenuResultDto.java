package com.erp.modules.products.domain.dto;

/**
 * What a branch-menu copy did (ADR-0073).
 *
 * @param itemsNowSold      items the target branch now sells because the source does
 * @param pricesCopied      of those, how many came with the source's branch price
 * @param alreadySetAtTarget items left alone because the target already had an entry for them
 */
public record CopyBranchMenuResultDto(int itemsNowSold, int pricesCopied, int alreadySetAtTarget) {
}
