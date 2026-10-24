package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreatePrepStationRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantTableRequest;
import com.erp.modules.sales.domain.dto.PrepStationCategoryDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.ReplaceCategoryRoutesRequest;
import com.erp.modules.sales.domain.dto.RestaurantTableDto;
import com.erp.modules.sales.domain.dto.UpdatePrepStationRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantTableRequest;
import java.util.List;

/**
 * Restaurant setup for a branch (ADR-0065 D-3, D-4): the floor's tables, the prep stations and
 * the category → station routing. Every {@code branchUid} parameter may be null, meaning the
 * caller's active branch.
 */
public interface RestaurantSetupService {

    /** Every table on the branch's floor (inactive ones included), each with its live state. */
    List<RestaurantTableDto> listTables(String branchUid);

    RestaurantTableDto createTable(CreateRestaurantTableRequest request);

    RestaurantTableDto updateTable(String uid, UpdateRestaurantTableRequest request);

    List<PrepStationDto> listStations(String branchUid);

    PrepStationDto createStation(CreatePrepStationRequest request);

    PrepStationDto updateStation(String uid, UpdatePrepStationRequest request);

    List<PrepStationCategoryDto> listCategoryRoutes(String branchUid);

    /** Replaces the branch's whole routing (an empty list clears it). */
    List<PrepStationCategoryDto> replaceCategoryRoutes(ReplaceCategoryRoutesRequest request);

    /** The distinct product categories in use in the caller's company, for the routing screen. */
    List<String> listProductCategories();
}
