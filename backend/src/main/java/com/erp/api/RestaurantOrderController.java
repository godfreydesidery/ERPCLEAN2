package com.erp.api;

import com.erp.modules.sales.domain.dto.AddRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.BillRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CancelRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.FireRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.MergeRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.TransferRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.RestaurantSplitResultDto;
import com.erp.modules.sales.domain.dto.SplitRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PayRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PosSaleConflictDto;
import com.erp.modules.sales.domain.dto.PrepSlipDto;
import com.erp.modules.sales.domain.dto.RestaurantBillDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderDto;
import com.erp.modules.sales.domain.dto.RestaurantPaymentDto;
import com.erp.modules.sales.domain.dto.RestaurantSendResultDto;
import com.erp.modules.sales.domain.dto.RestaurantVoidResultDto;
import com.erp.modules.sales.domain.dto.SendRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.VoidRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.service.RestaurantOrderService;
import com.erp.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restaurant orders — the open check (ADR-0065 D-5 to D-10).
 *
 * <p>Order → {@code /send} (one slip per prep station; the till prints them) → {@code /bill}
 * (non-fiscal) → {@code /pay} (becomes an ordinary POS sale; frees the table).
 *
 * <p>Void and cancel admit any order-taker at the door: whether they may actually do it is decided
 * in the service — they hold {@code RESTAURANT.ORDER.VOID}, or a manager approved at the till.
 */
@RestController
@RequestMapping("/api/v1/restaurant/orders")
public class RestaurantOrderController {

    private final RestaurantOrderService orders;

    public RestaurantOrderController(RestaurantOrderService orders) {
        this.orders = orders;
    }

    /** Live orders (OPEN, BILLED) by default; {@code status} may name others, repeatable. */
    @GetMapping
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public List<RestaurantOrderDto> list(@RequestParam(required = false) String branchUid,
                                         @RequestParam(required = false) List<RestaurantOrderStatus> status,
                                         @RequestParam(required = false) String tableUid) {
        return orders.list(branchUid, status, tableUid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto get(@PathVariable String uid) {
        return orders.get(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto open(@Valid @RequestBody CreateRestaurantOrderRequest request) {
        return orders.open(request);
    }

    @PatchMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto update(@PathVariable String uid,
                                     @Valid @RequestBody UpdateRestaurantOrderRequest request) {
        return orders.update(uid, request);
    }

    @PostMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto addLine(@PathVariable String uid,
                                      @Valid @RequestBody AddRestaurantOrderLineRequest request) {
        return orders.addLine(uid, request);
    }

    @PatchMapping("/uid/{uid}/lines/uid/{lineUid}")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto updateLine(@PathVariable String uid, @PathVariable String lineUid,
                                         @Valid @RequestBody UpdateRestaurantOrderLineRequest request) {
        return orders.updateLine(uid, lineUid, request);
    }

    @DeleteMapping("/uid/{uid}/lines/uid/{lineUid}")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto deleteLine(@PathVariable String uid, @PathVariable String lineUid) {
        return orders.deleteLine(uid, lineUid);
    }

    @PostMapping("/uid/{uid}/send")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantSendResultDto send(@PathVariable String uid,
                                       @Valid @RequestBody(required = false) SendRestaurantOrderRequest request) {
        return orders.send(uid, request == null ? null : request.waiterUid());
    }

    /** Releases held items — of one course, or all — and sends them ("mains now"). */
    @PostMapping("/uid/{uid}/fire")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantSendResultDto fire(@PathVariable String uid,
                                       @Valid @RequestBody(required = false) FireRestaurantOrderRequest request) {
        return orders.fire(uid, request);
    }

    /** Rebuilds slips to reprint: {@code round} absent = the latest. */
    @GetMapping("/uid/{uid}/slips")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public List<PrepSlipDto> slips(@PathVariable String uid,
                                   @RequestParam(required = false) Integer round,
                                   @RequestParam(required = false) String stationUid) {
        return orders.slips(uid, round, stationUid);
    }

    @PostMapping("/uid/{uid}/lines/uid/{lineUid}/void")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.ORDER.VOID')")
    public RestaurantVoidResultDto voidLine(@PathVariable String uid, @PathVariable String lineUid,
                                            @Valid @RequestBody VoidRestaurantOrderLineRequest request) {
        return orders.voidLine(uid, lineUid, request);
    }

    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.ORDER.VOID')")
    public RestaurantVoidResultDto cancel(@PathVariable String uid,
                                          @Valid @RequestBody(required = false) CancelRestaurantOrderRequest request) {
        return orders.cancel(uid, request);
    }

    /** Moves the chosen items onto a new check at the same table. */
    @PostMapping("/uid/{uid}/split")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantSplitResultDto split(@PathVariable String uid,
                                          @Valid @RequestBody SplitRestaurantOrderRequest request) {
        return orders.split(uid, request);
    }

    /** Moves every item of {@code fromOrderUid} onto this order and closes that one. */
    @PostMapping("/uid/{uid}/merge")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto merge(@PathVariable String uid,
                                    @Valid @RequestBody MergeRestaurantOrderRequest request) {
        return orders.merge(uid, request);
    }

    /** Hands the order to another waiter (ADR-0067): a cashier or manager, or with approval. */
    @PostMapping("/uid/{uid}/transfer")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantOrderDto transfer(@PathVariable String uid,
                                       @Valid @RequestBody TransferRestaurantOrderRequest request) {
        return orders.transfer(uid, request);
    }

    @PostMapping("/uid/{uid}/bill")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE')")
    public RestaurantBillDto bill(@PathVariable String uid,
                                  @Valid @RequestBody BillRestaurantOrderRequest request) {
        return orders.bill(uid, request);
    }

    /**
     * Takes payment: the order becomes a POS sale. Send an {@code Idempotency-Key} and reuse it on
     * a retry — the same sale comes back instead of a second one (ADR-0042 D-1).
     */
    @PostMapping("/uid/{uid}/pay")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') and @perm.has('POS.SALE.CREATE')")
    public RestaurantPaymentDto pay(@PathVariable String uid,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Valid @RequestBody PayRestaurantOrderRequest request) {
        return orders.pay(uid, idempotencyKey, request);
    }

    /**
     * Payment refusals carry the same machine-readable outcome as a plain POS sale
     * ({@link PosSaleController#handlePosSaleFlow}): 409 in flight, 422 refused (with
     * {@code data.errorCode}, e.g. {@code PRICE_CHANGED}), 410 stale. Nothing was posted on any of
     * them.
     */
    @ExceptionHandler(PosSaleFlowException.class)
    public ResponseEntity<ApiResponse<PosSaleConflictDto>> handlePosSaleFlow(PosSaleFlowException ex) {
        HttpStatus status = switch (ex.getStatus()) {
            case IN_FLIGHT    -> HttpStatus.CONFLICT;
            case REJECTED     -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STALE_REPLAY -> HttpStatus.GONE;
        };
        var body = new ApiResponse<>(
                new PosSaleConflictDto(ex.getStatus(), ex.getMessage(), ex.getErrorCode()),
                List.of(ex.getMessage()),
                null);
        return ResponseEntity.status(status)
                .header(PosSaleController.POS_SALE_STATUS_HEADER, ex.getStatus().name())
                .body(body);
    }
}
