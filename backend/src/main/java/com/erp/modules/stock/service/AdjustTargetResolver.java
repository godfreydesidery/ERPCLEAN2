package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Chooses the location a manual adjustment corrects (STK-01 / LBO-06) — shared by the single
 * Adjust Stock screen ({@link StockServiceImpl#adjust}) and the bulk stock sheet
 * ({@link StockImportHandler}) so both always land on the same row.
 *
 * <ul>
 *   <li>{@code locationUid} given → that location: same company, ACTIVE, in the given branch, and
 *       never the in-transit location (goods on the road are corrected by receiving them).</li>
 *   <li>otherwise rows at the in-transit location and rows holding zero are ignored — they are
 *       bookkeeping leftovers (a received transfer leaves a zero row at In-Transit), not stock anyone
 *       could have counted. Exactly one location left → that one; none → the branch default (first
 *       touch, or everything is zero); more than one → ambiguous, and the caller is asked to choose.</li>
 * </ul>
 *
 * <p>Before this, any second on-hand ROW — including the zero row every transfer receipt leaves at
 * In-Transit — made adjust refuse ("held at more than one location… adjust it from the location's
 * stock screen", a screen that did not exist) and the bulk sheet skip the product.
 */
@Component
public class AdjustTargetResolver {

    /** The location an adjustment lands on and the on-hand row already there (null on first touch). */
    public record Target(Long locationId, String locationUid, StockOnHand onHand) {}

    private final StockOnHandRepository   onHands;
    private final StockLocationRepository locations;
    private final LocationResolver        locationResolver;

    public AdjustTargetResolver(StockOnHandRepository onHands,
                                StockLocationRepository locations,
                                LocationResolver locationResolver) {
        this.onHands          = onHands;
        this.locations        = locations;
        this.locationResolver = locationResolver;
    }

    /**
     * @throws IllegalArgumentException when the location is unusable or, with no location given,
     *         the product is held at more than one location in the branch (friendly message).
     *
     * <p>Deliberately NOT {@code @Transactional}: it always runs inside the caller's transaction, and
     * a proxied method that throws marks that transaction rollback-only — which would turn the bulk
     * sheet's "skip this row" into a failed upload.
     */
    public Target resolve(Long companyId, Long branchId, Long productId, String locationUid) {
        List<StockOnHand> rows = onHands.findAllByCompanyIdAndBranchIdAndProductId(
                companyId, branchId, productId);

        if (locationUid != null && !locationUid.isBlank()) {
            StockLocation loc = locationResolver.resolveLocation(locationUid.trim(), companyId);
            if (!loc.getBranchId().equals(branchId)) {
                throw new IllegalArgumentException(
                        "The selected location belongs to another branch. Switch to that branch to "
                      + "adjust its stock.");
            }
            if (locationResolver.isInTransitLocation(loc)) {
                throw new IllegalArgumentException(
                        "Stock in transit can't be adjusted. Receive the transfer first, then adjust "
                      + "the location it arrived at.");
            }
            return new Target(loc.getId(), loc.getUid(), rowAt(rows, loc.getId()));
        }

        Long transitId = locationResolver.findInTransitLocationId(companyId, branchId).orElse(null);
        List<StockOnHand> holding = rows.stream()
                .filter(r -> r.getLocationId() != null && !r.getLocationId().equals(transitId))
                .filter(r -> r.getQuantity() != null && r.getQuantity().signum() != 0)
                .toList();
        if (holding.size() > 1) {
            String where = locations.findByCompanyIdAndIdIn(companyId,
                            holding.stream().map(StockOnHand::getLocationId).distinct().toList())
                    .stream().map(StockLocation::getName).sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "This product is held at more than one location in this branch"
                  + (where.isBlank() ? "" : " (" + where + ")")
                  + ". Choose the location to adjust.");
        }
        Long targetId = holding.size() == 1
                ? holding.get(0).getLocationId()
                : locationResolver.defaultLocationId(companyId, branchId);
        String targetUid = locations.findByCompanyIdAndId(companyId, targetId)
                .map(StockLocation::getUid).orElse(null);
        return new Target(targetId, targetUid, rowAt(rows, targetId));
    }

    private static StockOnHand rowAt(List<StockOnHand> rows, Long locationId) {
        return rows.stream()
                .filter(r -> locationId.equals(r.getLocationId()))
                .findFirst().orElse(null);
    }
}
