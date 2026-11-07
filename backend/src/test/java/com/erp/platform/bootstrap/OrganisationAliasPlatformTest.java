package com.erp.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** "platform" and its lookalikes are reserved for the platform organisation (owner, 2026-11-07). */
class OrganisationAliasPlatformTest {

    @ParameterizedTest
    @ValueSource(strings = {"Platform", "PLATFORM", "platform", "Pllatform", "Platfrom", "Platfom",
            "Plattform", "P1atform", "Pl@tform", "Platf0rm", "PlatfOrrn", "Plat-form", "P.l.a.t.f.o.r.m",
            "Platform Ltd", "The Platform Company", "  platform  ", "Platformm", "Patform",
            "Pla tform", "plaftorm"})
    void refusesPlatformAndLookalikes(String name) {
        assertThat(OrganisationAlias.resemblesPlatform(name)).as(name).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kilimanjaro", "Cafe Tulia", "Platinum Foods", "Plan & Form", "Plan and Form",
            "Plateau Traders", "Formula One", "Otapp Agency", "Plaza Mall", "Uniform Supplies",
            "Platformance Holdings", "PL", "Atlas Form"})
    void allowsOrdinaryNames(String name) {
        assertThat(OrganisationAlias.resemblesPlatform(name)).as(name).isFalse();
    }
}
