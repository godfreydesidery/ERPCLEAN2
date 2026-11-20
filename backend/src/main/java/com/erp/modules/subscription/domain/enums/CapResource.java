package com.erp.modules.subscription.domain.enums;

/** What a plan caps (ADR-0069 D-5). */
public enum CapResource {
    USERS("users"),
    BRANCHES("branches"),
    TILLS("POS tills"),
    COMPANIES("companies");

    private final String noun;

    CapResource(String noun) {
        this.noun = noun;
    }

    /** Plural noun for messages: "up to 5 users". */
    public String noun() {
        return noun;
    }
}
