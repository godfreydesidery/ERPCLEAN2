package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.ModifierGroupDto;
import com.erp.modules.sales.domain.dto.ModifierLinkDto;
import com.erp.modules.sales.domain.dto.SaveModifierGroupRequest;
import java.util.List;

/**
 * Restaurant modifiers (ADR-0066 D-4): the choices offered on menu items — "Milk", "Extras" — and
 * which items offer them. Company-wide, like the menu itself.
 */
public interface RestaurantMenuService {

    /** Every group of the caller's company, with options and items (inactive ones included). */
    List<ModifierGroupDto> listGroups();

    ModifierGroupDto createGroup(SaveModifierGroupRequest request);

    ModifierGroupDto updateGroup(String uid, SaveModifierGroupRequest request);

    /** For the till: each menu item that offers active groups, with their uids in order. */
    List<ModifierLinkDto> listLinks();
}
