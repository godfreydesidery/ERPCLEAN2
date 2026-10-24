package com.erp.api;

import com.erp.modules.sales.domain.dto.CreatePrepStationRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantTableRequest;
import com.erp.modules.sales.domain.dto.PrepStationCategoryDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.ReplaceCategoryRoutesRequest;
import com.erp.modules.sales.domain.dto.RestaurantTableDto;
import com.erp.modules.sales.domain.dto.UpdatePrepStationRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantTableRequest;
import com.erp.modules.sales.service.RestaurantSetupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restaurant setup per branch (ADR-0065 D-3, D-4, D-10): floor tables, prep stations, and the
 * category → station routing. Writes need {@code RESTAURANT.SETUP.MANAGE}; reads are also open to
 * {@code RESTAURANT.ORDER.MANAGE}, because every till needs the floor and the station printers.
 *
 * <p>{@code branchUid} is optional everywhere: absent means the caller's active branch. Company and
 * branch assignment are enforced in the service, from the loaded row for uid-addressed writes.
 */
@RestController
@RequestMapping("/api/v1/restaurant")
public class RestaurantSetupController {

    private final RestaurantSetupService setup;

    public RestaurantSetupController(RestaurantSetupService setup) {
        this.setup = setup;
    }

    // ---- tables ----

    @GetMapping("/tables")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<RestaurantTableDto> listTables(@RequestParam(required = false) String branchUid) {
        return setup.listTables(branchUid);
    }

    @PostMapping("/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public RestaurantTableDto createTable(@Valid @RequestBody CreateRestaurantTableRequest request) {
        return setup.createTable(request);
    }

    @PutMapping("/tables/uid/{uid}")
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public RestaurantTableDto updateTable(@PathVariable String uid,
                                          @Valid @RequestBody UpdateRestaurantTableRequest request) {
        return setup.updateTable(uid, request);
    }

    // ---- prep stations ----

    @GetMapping("/prep-stations")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<PrepStationDto> listStations(@RequestParam(required = false) String branchUid) {
        return setup.listStations(branchUid);
    }

    @PostMapping("/prep-stations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public PrepStationDto createStation(@Valid @RequestBody CreatePrepStationRequest request) {
        return setup.createStation(request);
    }

    @PutMapping("/prep-stations/uid/{uid}")
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public PrepStationDto updateStation(@PathVariable String uid,
                                        @Valid @RequestBody UpdatePrepStationRequest request) {
        return setup.updateStation(uid, request);
    }

    // ---- category routing ----

    @GetMapping("/category-routes")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<PrepStationCategoryDto> listCategoryRoutes(
            @RequestParam(required = false) String branchUid) {
        return setup.listCategoryRoutes(branchUid);
    }

    @PutMapping("/category-routes")
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<PrepStationCategoryDto> replaceCategoryRoutes(
            @Valid @RequestBody ReplaceCategoryRoutesRequest request) {
        return setup.replaceCategoryRoutes(request);
    }

    /** The product categories in use, to route on the routing screen. */
    @GetMapping("/product-categories")
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<String> listProductCategories() {
        return setup.listProductCategories();
    }
}
