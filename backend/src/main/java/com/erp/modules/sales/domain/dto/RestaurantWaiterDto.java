package com.erp.modules.sales.domain.dto;

/**
 * A waiter of a branch, for the tiles a till signs waiters in from (J COB 2026-10-30): the
 * username fills the sign-in, the name labels the tile. The password is still typed.
 */
public record RestaurantWaiterDto(String uid, String name, String username) {}
