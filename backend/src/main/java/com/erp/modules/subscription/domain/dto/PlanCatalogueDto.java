package com.erp.modules.subscription.domain.dto;

import com.erp.modules.subscription.domain.enums.AddOn;
import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.domain.enums.PlanModule;
import java.util.Arrays;
import java.util.List;

/** The plan catalogue for the platform console (ADR-0069 D-1, D-2). */
public record PlanCatalogueDto(List<Plan> plans, List<AddOnDto> addOns) {

    /** A {@code null} cap means unlimited. */
    public record Plan(String code, String name, int rank, Integer maxUsers, Integer maxBranches,
                       Integer maxTills, Integer maxCompanies, List<ModuleDto> modules) {
    }

    public record ModuleDto(String code, String name) {

        public static ModuleDto of(PlanModule m) {
            return new ModuleDto(m.name(), m.displayName());
        }
    }

    public record AddOnDto(String code, String name) {
    }

    public static PlanCatalogueDto current() {
        List<Plan> plans = Arrays.stream(PlanCode.values())
                .map(p -> new Plan(p.name(), p.displayName(), p.rank(),
                        p.cap(CapResource.USERS), p.cap(CapResource.BRANCHES),
                        p.cap(CapResource.TILLS), p.cap(CapResource.COMPANIES),
                        p.modules().stream().map(ModuleDto::of).toList()))
                .toList();
        List<AddOnDto> addOns = Arrays.stream(AddOn.values())
                .map(a -> new AddOnDto(a.name(), a.displayName()))
                .toList();
        return new PlanCatalogueDto(plans, addOns);
    }
}
