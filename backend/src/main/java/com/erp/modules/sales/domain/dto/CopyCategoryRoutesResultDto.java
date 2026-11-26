package com.erp.modules.sales.domain.dto;

import java.util.List;

/**
 * What a routing copy did.
 *
 * @param routesCopied              departments now routed at the target
 * @param alreadyRouted             departments left alone: the target already routes them
 * @param skippedNoMatchingStation  departments skipped: the target has no station of that name
 * @param stationsMissingAtTarget   the source station names the target lacks (create them, then
 *                                  copy again to route those departments too)
 */
public record CopyCategoryRoutesResultDto(int routesCopied, int alreadyRouted,
                                          int skippedNoMatchingStation,
                                          List<String> stationsMissingAtTarget) {
}
