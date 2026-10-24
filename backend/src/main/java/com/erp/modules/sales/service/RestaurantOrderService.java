package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AddRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.BillRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CancelRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.MergeRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.RestaurantSplitResultDto;
import com.erp.modules.sales.domain.dto.SplitRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PayRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PrepSlipDto;
import com.erp.modules.sales.domain.dto.RestaurantBillDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderDto;
import com.erp.modules.sales.domain.dto.RestaurantPaymentDto;
import com.erp.modules.sales.domain.dto.RestaurantSendResultDto;
import com.erp.modules.sales.domain.dto.RestaurantVoidResultDto;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.VoidRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import java.util.List;

/**
 * Restaurant orders — open checks on the server (ADR-0065 D-5 to D-9).
 *
 * <p>Order → Send (slips per prep station) → Bill (non-fiscal) → Pay (becomes a POS sale, frees the
 * table). Every write locks the order row first, so concurrent devices cannot fire the same items
 * twice or pay the same order twice.
 */
public interface RestaurantOrderService {

    /**
     * The branch's orders in the given states (null/empty = the live ones, OPEN and BILLED),
     * optionally for one table. {@code branchUid} null means the caller's active branch.
     */
    List<RestaurantOrderDto> list(String branchUid, List<RestaurantOrderStatus> statuses,
                                  String tableUid);

    RestaurantOrderDto get(String uid);

    RestaurantOrderDto open(CreateRestaurantOrderRequest request);

    RestaurantOrderDto update(String uid, UpdateRestaurantOrderRequest request);

    RestaurantOrderDto addLine(String uid, AddRestaurantOrderLineRequest request);

    RestaurantOrderDto updateLine(String uid, String lineUid, UpdateRestaurantOrderLineRequest request);

    /** Deletes an UNSENT item. A sent one must be voided. */
    RestaurantOrderDto deleteLine(String uid, String lineUid);

    /** Fires every unsent item and returns one slip per prep station to print. */
    RestaurantSendResultDto send(String uid);

    /**
     * Rebuilds slips for reprinting: round {@code round} (null = the latest), optionally for one
     * station. Only items still SENT are on it.
     */
    List<PrepSlipDto> slips(String uid, Integer round, String stationUid);

    RestaurantVoidResultDto voidLine(String uid, String lineUid, VoidRestaurantOrderLineRequest request);

    RestaurantBillDto bill(String uid, BillRestaurantOrderRequest request);

    RestaurantPaymentDto pay(String uid, String idempotencyKey, PayRestaurantOrderRequest request);

    RestaurantVoidResultDto cancel(String uid, CancelRestaurantOrderRequest request);

    /** Moves the chosen items onto a new check at the same table (ADR-0066 D-2). */
    RestaurantSplitResultDto split(String uid, SplitRestaurantOrderRequest request);

    /** Moves every item of another live order onto this one and closes it (ADR-0066 D-3). */
    RestaurantOrderDto merge(String uid, MergeRestaurantOrderRequest request);
}
