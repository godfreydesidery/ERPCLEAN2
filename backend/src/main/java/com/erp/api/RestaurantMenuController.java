package com.erp.api;

import com.erp.modules.sales.domain.dto.ModifierGroupDto;
import com.erp.modules.sales.domain.dto.ModifierLinkDto;
import com.erp.modules.sales.domain.dto.SaveModifierGroupRequest;
import com.erp.modules.sales.service.RestaurantMenuService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restaurant modifiers (ADR-0066 D-4): the choices offered on menu items. Writes need
 * {@code RESTAURANT.SETUP.MANAGE}; reads are open to order-takers, whose till offers the choices.
 */
@RestController
@RequestMapping("/api/v1/restaurant")
public class RestaurantMenuController {

    private final RestaurantMenuService menu;

    public RestaurantMenuController(RestaurantMenuService menu) {
        this.menu = menu;
    }

    @GetMapping("/modifier-groups")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<ModifierGroupDto> listGroups() {
        return menu.listGroups();
    }

    @PostMapping("/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public ModifierGroupDto createGroup(@Valid @RequestBody SaveModifierGroupRequest request) {
        return menu.createGroup(request);
    }

    @PutMapping("/modifier-groups/uid/{uid}")
    @PreAuthorize("@perm.has('RESTAURANT.SETUP.MANAGE')")
    public ModifierGroupDto updateGroup(@PathVariable String uid,
                                        @Valid @RequestBody SaveModifierGroupRequest request) {
        return menu.updateGroup(uid, request);
    }

    /** Which items offer which groups — loaded once by the till. */
    @GetMapping("/modifier-links")
    @PreAuthorize("@perm.has('RESTAURANT.ORDER.MANAGE') or @perm.has('RESTAURANT.SETUP.MANAGE')")
    public List<ModifierLinkDto> listLinks() {
        return menu.listLinks();
    }
}
