package com.erp.modules.subscription.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * The four subscription tiers (ADR-0069 D-1). The catalogue lives in code: each tier's caps and the
 * modules it contains. A tier contains every module of the tiers below it. A {@code null} cap means
 * unlimited.
 */
public enum PlanCode {

    BASIC("Basic", 1, 5, 1, 3, 1),
    STANDARD("Standard", 2, 20, 5, 10, 2),
    PREMIUM("Premium", 3, 50, 15, 30, 3),
    ENTERPRISE("Enterprise", 4, null, null, null, null);

    private final String displayName;
    private final int rank;
    private final Integer maxUsers;
    private final Integer maxBranches;
    private final Integer maxTills;
    private final Integer maxCompanies;

    PlanCode(String displayName, int rank, Integer maxUsers, Integer maxBranches, Integer maxTills,
             Integer maxCompanies) {
        this.displayName = displayName;
        this.rank = rank;
        this.maxUsers = maxUsers;
        this.maxBranches = maxBranches;
        this.maxTills = maxTills;
        this.maxCompanies = maxCompanies;
    }

    public String displayName() {
        return displayName;
    }

    public int rank() {
        return rank;
    }

    /** The plan's own cap for {@code resource}; {@code null} = unlimited. */
    public Integer cap(CapResource resource) {
        return switch (resource) {
            case USERS -> maxUsers;
            case BRANCHES -> maxBranches;
            case TILLS -> maxTills;
            case COMPANIES -> maxCompanies;
        };
    }

    /** True when this tier contains {@code module}. Add-on modules are never part of a tier. */
    public boolean includes(PlanModule module) {
        return module.addOn() == null && module.minPlan().rank <= rank;
    }

    /** The modules this tier contains, in declaration order (add-ons excluded). */
    public List<PlanModule> modules() {
        return Arrays.stream(PlanModule.values()).filter(this::includes).toList();
    }
}
