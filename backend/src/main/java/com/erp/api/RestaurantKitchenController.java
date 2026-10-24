package com.erp.api;

import com.erp.modules.sales.domain.dto.KitchenBumpRequest;
import com.erp.modules.sales.domain.dto.KitchenTicketDto;
import com.erp.modules.sales.service.RestaurantKitchenService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The kitchen display (ADR-0066 D-6): what a station has to make, and bumping it ready. The
 * station's branch must be one the caller is assigned to (checked in the service).
 */
@RestController
@RequestMapping("/api/v1/restaurant/kitchen")
public class RestaurantKitchenController {

    private final RestaurantKitchenService kitchen;

    public RestaurantKitchenController(RestaurantKitchenService kitchen) {
        this.kitchen = kitchen;
    }

    @GetMapping
    @PreAuthorize("@perm.has('RESTAURANT.KITCHEN.MANAGE')")
    public List<KitchenTicketDto> tickets(@RequestParam String stationUid) {
        return kitchen.tickets(stationUid);
    }

    @PostMapping("/ready")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('RESTAURANT.KITCHEN.MANAGE')")
    public void ready(@Valid @RequestBody KitchenBumpRequest request) {
        kitchen.ready(request.lineUids());
    }

    @PostMapping("/recall")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('RESTAURANT.KITCHEN.MANAGE')")
    public void recall(@Valid @RequestBody KitchenBumpRequest request) {
        kitchen.recall(request.lineUids());
    }
}
