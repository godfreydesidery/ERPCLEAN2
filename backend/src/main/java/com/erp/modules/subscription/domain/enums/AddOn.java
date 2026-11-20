package com.erp.modules.subscription.domain.enums;

/** Paid add-ons any tier can buy (ADR-0069 D-2). */
public enum AddOn {
    RESTAURANT("Restaurant"),
    ORBIXHQ("OrbixHQ app");

    private final String displayName;

    AddOn(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
